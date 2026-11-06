import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker, { signData } from "../src/index.ts";

// D1 흉내: 실제 schema.sql을 메모리 SQLite에
function d1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));
  return {
    db,
    prepare(sql: string) {
      let args: any[] = [];
      const st = {
        bind(...a: any[]) { args = a; return st; },
        async run() { db.prepare(sql).run(...args); return { meta: { changes: 1 } }; },
        async first() { return db.prepare(sql).get(...args) ?? null; },
        async all() { return { results: db.prepare(sql).all(...args) }; },
      };
      return st;
    },
  };
}

const OWNER = "01011112222", GUEST = "01055556789"; // 가상 번호
const CID = "CAe210942f21a47ab8e7eb36072fff5480";
const mkEnv = () => ({ DB: d1(), CAR_TOKEN: "t", CALL_CONTEXT_SECRET: "cc", OWNER_NUMBER: OWNER, CLAWOPS_SIGNING_KEY: "k" }) as any;
const rpc = (env: any, body: object, path = "/mcp/cc") =>
  worker.fetch(new Request("https://x" + path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ jsonrpc: "2.0", ...body }) }), env);
const ring = async (env: any, from: string, id = CID) =>
  ((await (await worker.fetch(new Request("https://x/clawops/call-context/cc", { method: "POST", body: `CallId=${id}&From=${from}` }), env)).json()) as any).CallContext;
// 서명 웹훅: 받아쓰기·요약 이벤트를 /clawops/events로
const hook = async (env: any, from: string, Event = "transcript.completed", more: Record<string, string> = {}) => {
  const raw = new URLSearchParams({ CallId: CID, From: from, Direction: "inbound", Event, ...more }).toString();
  const mac = await crypto.subtle.sign("HMAC", await crypto.subtle.importKey("raw", new TextEncoder().encode("k"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]), new TextEncoder().encode(signData("https://x/clawops/events", null, raw)));
  await worker.fetch(new Request("https://x/clawops/events", { method: "POST", headers: { "x-signature": btoa(String.fromCharCode(...new Uint8Array(mac))) }, body: raw }), env);
};
const ASR = [{ speaker: "speaker_0", text: "안녕하세요" }, { speaker: "speaker_1", text: "이지은입니다." }, { speaker: "speaker_0", text: "네" }, { speaker: "speaker_1", text: "계약서 건이요." }];
// 받아쓰기 fetch를 가짜로(요약 호출이 오면 memo). 쓴 쪽이 finally에서 되돌린다
const fakeFetch = (memo?: object) => {
  const real = globalThis.fetch, st = { calls: 0, sent: "", real };
  globalThis.fetch = (async (u: string, init: any) => {
    st.calls++;
    if (String(u).includes("anthropic")) { st.sent = init.body; return new Response(JSON.stringify({ content: [{ type: "text", text: JSON.stringify(memo) }] })); }
    return new Response(JSON.stringify({ segments: ASR }));
  }) as unknown as typeof fetch;
  return st;
};
const mkEnvA = (more: object = {}) => ({ ...mkEnv(), CLAWOPS_API_KEY: "a", CLAWOPS_ACCOUNT_ID: "b", ...more });
const cards = (env: any) => env.DB.db.prepare("SELECT * FROM inbox WHERE kind='call'").all() as any[];

test("MCP: initialize·알림·tools/list, 비밀 틀리면 404, GET 405", async () => {
  const env = mkEnv();
  const i = (await (await rpc(env, { id: 1, method: "initialize", params: { protocolVersion: "2025-03-26" } })).json()) as any;
  assert.equal(i.result.protocolVersion, "2025-03-26");
  assert.deepEqual(i.result.capabilities, { tools: {} });
  assert.equal((await rpc(env, { method: "notifications/initialized" })).status, 202);
  const l = (await (await rpc(env, { id: 2, method: "tools/list" })).json()) as any;
  assert.deepEqual(l.result.tools, []);

  assert.equal((await rpc(env, { id: 1, method: "initialize" }, "/mcp/nope")).status, 404);
  assert.equal((await worker.fetch(new Request("https://x/mcp/cc"), env)).status, 405);
});

test("tools/call: 도구가 없으니 무엇을 불러도 unknown tool 오류, 카드 없음", async () => {
  const env = mkEnv();
  for (const name of ["report_call", "x"]) {
    const r = (await (await rpc(env, { id: 3, method: "tools/call", params: { name, arguments: { call_id: CID, summary: "x" } } })).json()) as any;
    assert.equal(r.result.isError, true);
    assert.equal(r.result.content[0].text, "unknown tool");
  }
  assert.equal(cards(env).length, 0);
});

test("transcript.completed → 카드 1장, 뒤에 온 summary.completed는 건너뜀", async () => {
  const f = fakeFetch();
  try {
    const env = mkEnvA();
    await ring(env, GUEST);
    await hook(env, GUEST);
    await hook(env, GUEST, "summary.completed", { SummaryJson: JSON.stringify({ coreSummary: "나중 요약" }) });
    const c = cards(env);
    assert.equal(c.length, 1);
    assert.equal(c[0].summary, "상대 말: 이지은입니다. 계약서 건이요.");
    assert.equal(c[0].from_number, GUEST);
    assert.equal(c[0].status, "summarized");
  } finally { globalThis.fetch = f.real; }
});

test("transcript.completed: 키 없으면 상대 말 그대로 카드, 주인·이미 카드 있는 통화는 건너뜀", async () => {
  const f = fakeFetch();
  try {
    const env = mkEnvA();
    await hook(env, OWNER);
    assert.equal(cards(env).length, 0);
    await hook(env, GUEST);
    await hook(env, GUEST); // 재전송돼도 1장
    assert.equal(cards(env).length, 1);
    assert.equal(cards(env)[0].summary, "상대 말: 이지은입니다. 계약서 건이요.");
    const before = f.calls;
    const env2 = mkEnvA();
    await ring(env2, GUEST);
    await hook(env2, GUEST, "summary.completed", { SummaryJson: JSON.stringify({ coreSummary: "미팅 변경" }) }); // 이미 카드가 있다
    await hook(env2, GUEST);
    assert.equal(cards(env2).length, 1);
    assert.equal(cards(env2)[0].summary, "미팅 변경");
    assert.equal(f.calls, before); // 받아쓰기 fetch 안 함
  } finally { globalThis.fetch = f.real; }
});

test("transcript.completed: 받아쓰기를 AI가 요약한 메모로 카드, 이름표·시각 저장", async () => {
  const f = fakeFetch({ caller: "이지은", summary: "이지은 님이 계약서 건으로 3시까지 연락을 원합니다.", callback_by: "3시까지" });
  try {
    const env = mkEnvA({ ANTHROPIC_API_KEY: "k" });
    await hook(env, GUEST);
    const c = cards(env);
    assert.equal(c.length, 1);
    assert.equal(c[0].from_label, "이지은");
    assert.equal(c[0].summary, "이지은 님이 계약서 건으로 3시까지 연락을 원합니다.");
    assert.equal(JSON.parse(c[0].meta).callback_by, "3시까지");
    assert.match(JSON.parse(f.sent).messages[0].content, /비서: 안녕하세요\n상대: 이지은입니다/);
  } finally { globalThis.fetch = f.real; }
});

test("transcript.completed: 주인 통화는 시킨 일마다 할 일 카드, 재전송 1벌, 묻기만 하면 없음", async () => {
  const real = globalThis.fetch; let tasks = ["내일 오전 박도윤 님께 견적서 보내기", "메일: 이지은 / 회의 안내 / 내일 10시"];
  globalThis.fetch = (async (u: string) => new Response(JSON.stringify(String(u).includes("anthropic")
    ? { content: [{ type: "text", text: JSON.stringify({ tasks }) }] }
    : { segments: [{ speaker: "speaker_0", text: "네" }, { speaker: "speaker_1", text: "할 일로 남겨줘" }] }))) as unknown as typeof fetch;
  try {
    const env = mkEnvA({ ANTHROPIC_API_KEY: "k" });
    await hook(env, OWNER);
    await hook(env, OWNER);
    const rows = env.DB.db.prepare("SELECT summary FROM inbox WHERE kind='task'").all().map((r: any) => r.summary);
    assert.deepEqual(rows, tasks);
    tasks = [];
    const env2 = mkEnvA({ ANTHROPIC_API_KEY: "k" });
    await hook(env2, OWNER);
    assert.equal(env2.DB.db.prepare("SELECT count(*) n FROM inbox WHERE kind='task'").get().n, 0);
  } finally { globalThis.fetch = real; }
});

test("transcript.completed: 주인 통화는 카드 없음, owner_as_guest 켜면 손님처럼 카드", async () => {
  const f = fakeFetch();
  try {
    const env = mkEnvA();
    await hook(env, OWNER);
    assert.equal(cards(env).length, 0);
    env.DB.db.prepare("INSERT INTO kv(key,value,updated_at) VALUES('owner_as_guest','true',0)").run();
    await hook(env, OWNER);
    assert.equal(cards(env).length, 1);
  } finally { globalThis.fetch = f.real; }
});

test("call-context: 어떤 대본에도 report_call이 없다(카드는 통화 뒤 받아쓰기로)", async () => {
  const env = mkEnv();
  assert.doesNotMatch((await ring(env, GUEST)).Instruction, /report_call/);
  assert.doesNotMatch((await ring(env, OWNER)).Instruction, /report_call/);
});

// 주인 통화: 되읽은 문자는 끊은 뒤 자동 발송. 받아쓰기·요약·중계 fetch를 가짜로(쓴 쪽이 finally에서 되돌린다)
const ownerFetch = (memo: object, relayStatus = 200) => {
  const real = globalThis.fetch, st = { relay: [] as any[], real };
  globalThis.fetch = (async (u: string, init: any) => {
    if (String(u).includes("relay.test")) { st.relay.push({ headers: init.headers, ...JSON.parse(init.body) }); return new Response(JSON.stringify(relayStatus === 200 ? { messageId: "m1", status: "queued" } : { code: "boom" }), { status: relayStatus }); }
    if (String(u).includes("anthropic")) return new Response(JSON.stringify({ content: [{ type: "text", text: JSON.stringify(memo) }] }));
    return new Response(JSON.stringify({ segments: [{ speaker: "speaker_0", text: "네" }, { speaker: "speaker_1", text: "문자 보내줘" }] }));
  }) as unknown as typeof fetch;
  return st;
};
const mkEnvS = () => mkEnvA({ ANTHROPIC_API_KEY: "k", SMS_RELAY_URL: "https://relay.test/send", SMS_RELAY_SECRET: "s" });
const rows = (env: any, kind: string) => env.DB.db.prepare("SELECT * FROM inbox WHERE kind=? ORDER BY id").all(kind) as any[];
const callCard = (env: any, label: string, num: string | null) => env.DB.db.prepare("INSERT INTO inbox(kind,status,from_label,from_number,created_at) VALUES('call','summarized',?,?,0)").run(label, num);
const kvSet = (env: any, key: string, v: unknown) => env.DB.db.prepare("INSERT INTO kv(key,value,updated_at) VALUES(?,?,0)").run(key, JSON.stringify(v));
const SEND = { to: "이지은", body: "3시에 도착합니다." };

test("주인 통화: 최근 전화 카드에 이름이 있으면 자동 발송, sent 행이 남고 할 일 카드는 없음", async () => {
  const f = ownerFetch({ tasks: [], sends: [SEND] });
  try {
    const env = mkEnvS();
    callCard(env, "이지은", GUEST);
    await hook(env, OWNER);
    assert.equal(f.relay.length, 1);
    assert.equal(f.relay[0].to, GUEST);
    assert.equal(f.relay[0].body, SEND.body);
    assert.equal(f.relay[0].headers["x-relay-secret"], "s");
    const s = rows(env, "sent");
    assert.equal(s.length, 1);
    assert.equal(s[0].status, "sent");
    assert.equal(s[0].call_id, CID);
    assert.equal(s[0].summary, SEND.body);
    assert.equal(rows(env, "task").length, 0);
  } finally { globalThis.fetch = f.real; }
});

test("주인 통화: 연락처 이름으로 발송, '님'을 붙여도 일치", async () => {
  const f = ownerFetch({ tasks: [], sends: [{ to: "박도윤 님", body: "회의 10분 늦어요." }] });
  try {
    const env = mkEnvS();
    kvSet(env, "contacts", { 박도윤: "010-2222-3333".replace(/-/g, "") });
    await hook(env, OWNER);
    assert.equal(f.relay.length, 1);
    assert.equal(f.relay[0].to, "01022223333");
    assert.equal(rows(env, "task").length, 0);
  } finally { globalThis.fetch = f.real; }
});

test("주인 통화: 일정의 연락처 이름 첫 단어로도 번호를 찾음", async () => {
  const f = ownerFetch({ tasks: [], sends: [{ to: "김민수", body: "곧 도착해요." }] });
  try {
    const env = mkEnvS();
    kvSet(env, "schedule", [{ title: "미팅", contact: "김민수 팀장", contact_number: "01044445555" }]);
    await hook(env, OWNER);
    assert.equal(f.relay[0].to, "01044445555");
  } finally { globalThis.fetch = f.real; }
});

test("주인 통화: 번호를 못 찾으면 발송 없이 할 일 카드로 남김", async () => {
  const f = ownerFetch({ tasks: [], sends: [SEND] });
  try {
    const env = mkEnvS();
    await hook(env, OWNER);
    assert.equal(f.relay.length, 0);
    assert.deepEqual(rows(env, "task").map((r) => r.summary), ["이지은에게 문자 / 내용: 3시에 도착합니다."]);
    assert.equal(rows(env, "sent").length, 0);
  } finally { globalThis.fetch = f.real; }
});

test("주인 통화: 비슷한 이름(박도연 vs 박도윤)은 보내지 않고 할 일 카드", async () => {
  const f = ownerFetch({ tasks: [], sends: [{ to: "박도연", body: "연락 주세요." }] });
  try {
    const env = mkEnvS();
    callCard(env, "박도윤", GUEST);
    kvSet(env, "contacts", { 박도윤: GUEST });
    await hook(env, OWNER);
    assert.equal(f.relay.length, 0);
    assert.deepEqual(rows(env, "task").map((r) => r.summary), ["박도연에게 문자 / 내용: 연락 주세요."]);
  } finally { globalThis.fetch = f.real; }
});

test("주인 통화: 가짜 시연 번호는 보내지 않음", async () => {
  const f = ownerFetch({ tasks: [], sends: [SEND] });
  try {
    const env = mkEnvS();
    callCard(env, "이지은", "01000001234");
    await hook(env, OWNER);
    assert.equal(f.relay.length, 0);
    assert.equal(rows(env, "task").length, 1);
  } finally { globalThis.fetch = f.real; }
});

test("주인 통화: 중계가 실패하면 할 일 카드가 남고 sent 행은 failed", async () => {
  const f = ownerFetch({ tasks: [], sends: [SEND] }, 500);
  try {
    const env = mkEnvS();
    callCard(env, "이지은", GUEST);
    await hook(env, OWNER);
    assert.equal(f.relay.length, 1);
    assert.equal(rows(env, "sent")[0].status, "failed");
    assert.deepEqual(rows(env, "task").map((r) => r.summary), ["이지은에게 문자 / 내용: 3시에 도착합니다."]);
  } finally { globalThis.fetch = f.real; }
});

test("주인 통화: 같은 웹훅을 다시 보내도 문자는 한 번만", async () => {
  const f = ownerFetch({ tasks: [], sends: [SEND] });
  try {
    const env = mkEnvS();
    callCard(env, "이지은", GUEST);
    await hook(env, OWNER);
    await hook(env, OWNER);
    assert.equal(f.relay.length, 1);
    assert.equal(rows(env, "sent").length, 1);
  } finally { globalThis.fetch = f.real; }
});

test("주인 통화: 요약 실패면 자동 발송 없이 주인 말 그대로 할 일 1장", async () => {
  const f = ownerFetch(null as any);
  try {
    const env = mkEnvS();
    callCard(env, "이지은", GUEST);
    await hook(env, OWNER);
    assert.equal(f.relay.length, 0);
    assert.deepEqual(rows(env, "task").map((r) => r.summary), ["문자 보내줘"]);
  } finally { globalThis.fetch = f.real; }
});

test("/phone/contacts: 인증 없으면 401, 잘못된 번호·중복 이름 제외, 빈 배열이면 삭제", async () => {
  const env = mkEnv();
  const post = (body: object, auth = true) => worker.fetch(new Request("https://x/phone/contacts", { method: "POST", headers: auth ? { authorization: "Bearer t" } : {}, body: JSON.stringify(body) }), env);
  const kv = () => env.DB.db.prepare("SELECT value FROM kv WHERE key='contacts'").get();
  assert.equal((await post({ contacts: [] }, false)).status, 401);
  const r = (await (await post({ contacts: [
    { name: " 이지은 ", number: "010-5555-6789" }, { name: "이지은", number: "01099998888" }, { name: "김도윤", number: "02-123-4567" },
    { name: "", number: "01011112222" }, { name: "박도윤", number: "01011112222" },
  ] })).json()) as any;
  assert.deepEqual(r, { ok: true, count: 2 });
  assert.deepEqual(JSON.parse((kv() as any).value), { 이지은: "01055556789", 박도윤: "01011112222" });
  assert.deepEqual(await (await post({ contacts: [] })).json(), { ok: true, count: 0 });
  assert.equal(kv(), undefined);
});
