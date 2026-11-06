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

const OWNER = "01011112222", VIP = "01055556789", OTHER = "01077778888"; // 가상 번호
const CID = "CAe210942f21a47ab8e7eb36072fff5480", CID2 = "CAe210942f21a47ab8e7eb36072fff5481";
const mkEnv = () => ({ DB: d1(), CAR_TOKEN: "t", CALL_CONTEXT_SECRET: "cc", OWNER_NUMBER: OWNER, CLAWOPS_SIGNING_KEY: "k" }) as any;
const settings = (env: any, body: unknown) =>
  worker.fetch(new Request("https://x/car/settings", { method: "POST", headers: { authorization: "Bearer t" }, body: JSON.stringify(body) }), env);
const ring = async (env: any, from: string, id = CID) =>
  ((await (await worker.fetch(new Request("https://x/clawops/call-context/cc", { method: "POST", body: `CallId=${id}&From=${from}` }), env)).json()) as any).CallContext;
// 서명 웹훅: 받아쓰기·요약 이벤트를 /clawops/events로
const hook = async (env: any, from: string, id: string, Event = "transcript.completed", more: Record<string, string> = {}) => {
  const raw = new URLSearchParams({ CallId: id, From: from, Direction: "inbound", Event, ...more }).toString();
  const mac = await crypto.subtle.sign("HMAC", await crypto.subtle.importKey("raw", new TextEncoder().encode("k"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]), new TextEncoder().encode(signData("https://x/clawops/events", null, raw)));
  await worker.fetch(new Request("https://x/clawops/events", { method: "POST", headers: { "x-signature": btoa(String.fromCharCode(...new Uint8Array(mac))) }, body: raw }), env);
};
// 받아쓰기 fetch를 가짜로(요약 호출이 오면 memo). 쓴 쪽이 finally에서 되돌린다
const fakeFetch = (memo?: object) => {
  const st = { calls: 0, real: globalThis.fetch };
  globalThis.fetch = (async (u: string) => {
    st.calls++;
    if (String(u).includes("anthropic")) return new Response(JSON.stringify({ content: [{ type: "text", text: JSON.stringify(memo) }] }));
    return new Response(JSON.stringify({ segments: [{ speaker: "speaker_0", text: "안녕하세요" }, { speaker: "speaker_1", text: "견적 문의요." }] }));
  }) as unknown as typeof fetch;
  return st;
};
const mkEnvA = () => ({ ...mkEnv(), CLAWOPS_API_KEY: "a", CLAWOPS_ACCOUNT_ID: "b", ANTHROPIC_API_KEY: "k" });
const cards = (env: any) => env.DB.db.prepare("SELECT * FROM inbox WHERE kind='call' ORDER BY id").all() as any[];
const LINE = "바로 연결 목록에 넣은 분입니다";

test("direct_numbers: 저장·마스킹·삭제·잘못된 값 400", async () => {
  const env = mkEnv();
  const r = (await (await settings(env, { direct_numbers: ["010-5555-6789", "0212345678"] })).json()) as any;
  assert.deepEqual(r.settings.direct_labels, ["****6789", "****5678"]);
  assert.equal(r.settings.direct_numbers, undefined); // 원본 번호는 응답에 없음
  assert.deepEqual(JSON.parse(env.DB.db.prepare("SELECT value FROM kv WHERE key='settings'").get().value).direct_numbers, [VIP, "0212345678"]);
  for (const bad of ["01055556789", ["12345"], [1055556789], [VIP, "abc"], Array(11).fill(VIP)])
    assert.equal((await settings(env, { direct_numbers: bad })).status, 400);
  const d = (await (await settings(env, { direct_numbers: [] })).json()) as any;
  assert.equal(d.settings.direct_labels, undefined);
  assert.equal(JSON.parse(env.DB.db.prepare("SELECT value FROM kv WHERE key='settings'").get().value).direct_numbers, undefined);
});

test("call-context: 목록 번호면 direct 줄·Variables.direct, 아니면 없음, ment 모드는 줄 없음", async () => {
  const env = mkEnv();
  await settings(env, { owner_label: "만일", direct_numbers: [VIP] });
  const a = await ring(env, VIP);
  assert.ok(a.Instruction.includes(`이 분은 만일님이 ${LINE}. 용건만 짧게 듣고 '바로 전해 드리겠습니다'로 끝냅니다.`));
  assert.equal(a.Variables.direct, "1");
  const b = await ring(env, "+82" + VIP.slice(1), CID2); // +82 표기도 같은 번호
  assert.equal(b.Variables.direct, "1");
  const o = await ring(env, OTHER);
  assert.ok(!o.Instruction.includes(LINE));
  assert.equal(o.Variables.direct, undefined);
  await settings(env, { mode: "ment" });
  const m = await ring(env, VIP);
  assert.ok(!m.Instruction.includes(LINE));
});

test("카드: transcript.completed·summary.completed 모두 목록 번호면 urgent=1·meta.direct", async () => {
  const f = fakeFetch({ summary: "견적 문의" });
  try {
    const env = mkEnvA();
    await settings(env, { direct_numbers: [VIP] });
    await ring(env, VIP);
    await hook(env, VIP, CID);
    await ring(env, OTHER, CID2);
    await hook(env, OTHER, CID2);
    await hook(env, VIP, "CAe210942f21a47ab8e7eb36072fff5482", "summary.completed", { SummaryJson: JSON.stringify({ coreSummary: "안부 전화" }) });
    const c = cards(env);
    assert.equal(c.length, 3);
    assert.deepEqual([c[0].urgent, JSON.parse(c[0].meta).direct, JSON.parse(c[0].meta).via], [1, true, "transcript"]); // 기존 meta와 합쳐짐
    assert.deepEqual([c[1].urgent, JSON.parse(c[1].meta).direct], [0, undefined]);
    assert.deepEqual([c[2].urgent, JSON.parse(c[2].meta).direct], [1, true]);
  } finally { globalThis.fetch = f.real; }
});

test("ment_text: 저장·따옴표 괄호 제거·ment 대본 반영·빈 값이면 기본 문구·잘못된 값 400", async () => {
  const env = mkEnv();
  await settings(env, { mode: "ment" });
  assert.ok((await ring(env, OTHER)).Instruction.includes("'지금 운전 중이라 전화를 받을 수 없습니다. 정차하시면 연락드리겠습니다.'라고만"));
  const r = (await (await settings(env, { ment_text: " 지금 회의 중이에요. '곧' (연락) 드릴게요! " })).json()) as any;
  assert.equal(r.settings.ment_text, "지금 회의 중이에요. 곧 연락 드릴게요!");
  const ins = (await ring(env, OTHER)).Instruction;
  assert.ok(ins.includes("'지금 회의 중이에요. 곧 연락 드릴게요!'라고만 말하고, 말을 마치면 상대 대답을 기다리지 않고 즉시 통화를 종료합니다."));
  assert.ok(!ins.includes("운전 중이라"));
  for (const bad of ["가".repeat(61), "()", "a\nb", "a; b", "a:b", 5, "a<b>"])
    assert.equal((await settings(env, { ment_text: bad })).status, 400);
  assert.equal((await settings(env, { ment_text: "" })).status, 200);
  assert.ok((await ring(env, OTHER)).Instruction.includes("운전 중이라 전화를 받을 수 없습니다."));
});

test("바로 연결: 전화 오는 순간 ringing 카드, 받아쓰기 카드가 오면 새 id 행(meta.replaces)으로 바꾸고 ringing 행 삭제", async () => {
  const f = fakeFetch({ caller: "김민수", summary: "견적 문의" });
  try {
    const env = mkEnvA();
    await settings(env, { direct_numbers: [VIP] });
    await ring(env, OTHER, CID2);
    assert.equal(cards(env).length, 0); // 목록 밖 번호는 요약 전 카드 없음
    await ring(env, VIP);
    await ring(env, VIP); // 재전송돼도 1장
    let c = cards(env);
    assert.equal(c.length, 1);
    assert.deepEqual([c[0].status, c[0].urgent, c[0].call_id, c[0].summary, JSON.parse(c[0].meta).direct], ["ringing", 1, CID, "바로 연결 번호에서 전화가 왔어요", true]);
    const id = c[0].id;
    await hook(env, VIP, CID);
    c = cards(env);
    assert.equal(c.length, 1);
    assert.deepEqual([c[0].status, c[0].urgent, c[0].summary, c[0].from_label, JSON.parse(c[0].meta).via, JSON.parse(c[0].meta).direct], ["summarized", 1, "견적 문의", "김민수", "transcript", true]);
    assert.ok(c[0].id > id && JSON.parse(c[0].meta).replaces === id); // 차는 id>since만 받으므로 새 id + 지울 카드 id
    const inbox = (await (await worker.fetch(new Request("https://x/car/inbox?since=0", { headers: { authorization: "Bearer t" } }), env)).json()) as any;
    assert.equal(JSON.parse(inbox.items.find((i: any) => i.call_id === CID).meta).replaces, id); // 응답 items[].meta(JSON 문자열).replaces
    const before = f.calls;
    await hook(env, VIP, CID); // 이미 카드가 있으면 건너뜀(받아쓰기 fetch 안 함)
    assert.equal(f.calls, before);
    assert.equal(cards(env).length, 1);
  } finally { globalThis.fetch = f.real; }
});

test("/car/inbox: since>0은 오래된 순 50건 + more, 이어 받으면 유실 없음, since=0은 최신 50건", async () => {
  const env = mkEnv();
  const t = Date.now() - 1000;
  for (let i = 0; i < 60; i++) env.DB.db.prepare("INSERT INTO inbox(kind,created_at) VALUES('call',?)").run(t);
  const poll = async (since: number) => (await (await worker.fetch(new Request(`https://x/car/inbox?since=${since}`, { headers: { authorization: "Bearer t" } }), env)).json()) as any;
  const a = await poll(5);
  assert.deepEqual([a.items.length, a.items[0].id, a.items[49].id, a.more, a.min_id], [50, 6, 55, true, 1]);
  const b = await poll(55);
  assert.deepEqual([b.items.length, b.items[0].id, b.items[4].id, b.more], [5, 56, 60, false]);
  const z = await poll(0);
  assert.deepEqual([z.items.length, z.items[0].id, z.items[49].id, z.more], [50, 60, 11, false]);
});
