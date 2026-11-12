import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker, { signData } from "../src/index.ts";
import { parkingScript, reserveScript } from "../src/script.ts";
import { FALLBACK } from "../src/guard.ts";

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
        async run() { const r = db.prepare(sql).run(...args); return { meta: { changes: Number(r.changes), last_row_id: Number(r.lastInsertRowid) } }; },
        async first() { return db.prepare(sql).get(...args) ?? null; },
        async all() { return { results: db.prepare(sql).all(...args) }; },
      };
      return st;
    },
  };
}

const OWNER = "01011112222", SHOP = "02-000-0000", CID = "CAe210942f21a47ab8e7eb36072fff5480"; // 가상 번호
const mkEnv = (more: object = {}) => ({ DB: d1(), CAR_TOKEN: "t", CALL_CONTEXT_SECRET: "cc", OWNER_NUMBER: OWNER, CLAWOPS_SIGNING_KEY: "k", CLAWOPS_API_KEY: "a", CLAWOPS_ACCOUNT_ID: "b", CLAWOPS_NUMBER: "07000000000", ANTHROPIC_API_KEY: "k", ...more }) as any;
const reserve = (env: any, body: unknown, auth = "Bearer t") =>
  worker.fetch(new Request("https://x/car/reserve", { method: "POST", headers: { authorization: auth }, body: JSON.stringify(body) }), env);
const OK = { place: "가명 식당", arrive: "18:30", party: 4 };
const rows = (env: any, kind = "reserve") => env.DB.db.prepare("SELECT * FROM inbox WHERE kind=?").all(kind) as any[];

// 서명 웹훅(/clawops/events)
const hook = async (env: any, id: string, Direction: string, from = "07000000000", Event = "transcript.completed") => {
  const raw = new URLSearchParams({ CallId: id, From: from, Direction, Event }).toString();
  const mac = await crypto.subtle.sign("HMAC", await crypto.subtle.importKey("raw", new TextEncoder().encode("k"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]), new TextEncoder().encode(signData("https://x/clawops/events", null, raw)));
  await worker.fetch(new Request("https://x/clawops/events", { method: "POST", headers: { "x-signature": btoa(String.fromCharCode(...new Uint8Array(mac))) }, body: raw }), env);
};
const status = (env: any, body: string) => worker.fetch(new Request("https://x/clawops/status/cc", { method: "POST", body }), env);

// 가짜 fetch: 발신·받아쓰기·요약·카카오. 시험의 fetch는 전부 여기로(실제 전화·요청 없음)
const ASR = [{ speaker: "s0", text: "안녕하세요, AI 비서입니다. 오늘 6시 30분에 4명 예약 가능할까요? 주차도 되나요?" }, { speaker: "s1", text: "네 가능합니다. 주차는 02-000-0000 앞에 하세요." }];
const fake = (o: { memo?: object | null; segments?: object[]; callOk?: boolean; kakao?: object[] } = {}) => {
  const real = globalThis.fetch, st = { calls: [] as { url: string; method: string; body: any }[], restore: () => { globalThis.fetch = real; } };
  globalThis.fetch = (async (u: string, init: any = {}) => {
    const url = String(u), body = init.body ? (() => { try { return JSON.parse(init.body); } catch { return init.body; } })() : null;
    st.calls.push({ url, method: init.method ?? "GET", body });
    if (url.includes("kakao")) return new Response(JSON.stringify({ documents: o.kakao ?? [] }));
    if (url.includes("anthropic")) return new Response(JSON.stringify({ content: [{ type: "text", text: JSON.stringify(o.memo ?? null) }] }));
    if (url.endsWith("/calls")) return o.callOk === false ? new Response("{}", { status: 500 }) : new Response(JSON.stringify({ callId: CID }), { status: 201 });
    return new Response(JSON.stringify({ segments: o.segments ?? ASR }));
  }) as unknown as typeof fetch;
  return st;
};
const to = (st: ReturnType<typeof fake>, part: string) => st.calls.filter((c) => c.url.includes(part));
const place = async (env: any) => ((await (await reserve(env, OK)).json()) as any).id as number;

test("예약 입력 검증: 인증·장소·시각·인원·좌표, 실패는 발신도 행도 없음", async () => {
  const f = fake();
  try {
    const env = mkEnv();
    assert.equal((await reserve(env, OK, "Bearer x")).status, 401);
    for (const bad of [{ ...OK, place: "" }, { ...OK, place: " " }, { ...OK, place: "가".repeat(41) }, { ...OK, place: 5 },
      { ...OK, arrive: "25:00" }, { ...OK, arrive: "9:30" }, { ...OK, arrive: "18:60" }, { ...OK, arrive: 1830 },
      { ...OK, party: 0 }, { ...OK, party: 21 }, { ...OK, party: 2.5 }, { ...OK, party: "4" }, { ...OK, lat: "x" }, { ...OK, lng: {} }, "bad"])
      assert.equal((await reserve(env, bad)).status, 400, JSON.stringify(bad));
    assert.equal(f.calls.length, 0);
    assert.equal(rows(env).length, 0);
    assert.equal((await reserve(env, { ...OK, place: "가".repeat(40), party: 20, lat: 37.4, lng: "127.1" })).status, 200);
  } finally { f.restore(); }
});

test("O4_LIVE 없으면 To는 항상 OWNER_NUMBER(카카오 조회도 안 함), 발신 본문·inbox 행", async () => {
  const f = fake({ kakao: [{ phone: SHOP, place_name: "가명 식당" }] });
  try {
    const env = mkEnv({ KAKAO_REST_KEY: "kk", O4_LIVE: "0" });
    const r = (await (await reserve(env, { ...OK, lat: 37.4, lng: 127.1 })).json()) as any;
    assert.equal(r.ok, true);
    assert.equal(to(f, "kakao").length, 0);
    const c = to(f, "/calls")[0];
    assert.equal(c.url, "https://api.claw-ops.com/v1/accounts/b/calls");
    assert.equal(c.method, "POST");
    assert.equal(c.body.To, OWNER);
    assert.equal(c.body.From, "07000000000");
    assert.equal(c.body.AgentId, "cmw4rd0fb002i01s6grhclc21");
    assert.equal(c.body.Timeout, 30);
    assert.equal(c.body.StatusCallback, "https://x/clawops/status/cc");
    assert.deepEqual(c.body.CallContext.Variables, {});
    assert.match(c.body.CallContext.Instruction, /오늘 오후 6시 30분에 네 명 예약 가능할까요\? 주차도 되나요\?/);
    const [row] = rows(env);
    assert.equal(row.id, r.id);
    assert.deepEqual([row.kind, row.status, row.call_id, row.from_label, row.summary], ["reserve", "calling", CID, "가명 식당", "18:30 4명 예약 전화 중"]);
    assert.deepEqual(JSON.parse(row.meta), { party: 4, arrive: "18:30" });
  } finally { f.restore(); }
});

test("O4_LIVE=1이면 실제 가게 번호로, 번호 없으면 no_number(발신 안 함)", async () => {
  let f = fake({ kakao: [{ phone: SHOP, place_name: "가명 식당" }] });
  try {
    const env = mkEnv({ KAKAO_REST_KEY: "kk", O4_LIVE: "1" });
    assert.equal(((await (await reserve(env, { ...OK, lat: 37.4, lng: 127.1 })).json()) as any).ok, true);
    assert.equal(to(f, "/calls")[0].body.To, "020000000");
  } finally { f.restore(); }
  f = fake({ kakao: [] });
  try {
    const env = mkEnv({ KAKAO_REST_KEY: "kk", O4_LIVE: "1" });
    assert.deepEqual(await (await reserve(env, { ...OK, lat: 37.4, lng: 127.1 })).json(), { ok: false, code: "no_number" });
    assert.equal(((await (await reserve(env, OK)).json()) as any).code, "no_number"); // 좌표 없음
    assert.equal(to(f, "/calls").length, 0);
    assert.deepEqual(await (await reserve(mkEnv({ OWNER_NUMBER: undefined }), OK)).json(), { ok: false, code: "no_number" });
  } finally { f.restore(); }
});

test("발신 실패는 call_failed, inbox 행 없음", async () => {
  const f = fake({ callOk: false });
  try {
    const env = mkEnv();
    assert.deepEqual(await (await reserve(env, OK)).json(), { ok: false, code: "call_failed" });
    assert.equal(rows(env).length, 0);
  } finally { f.restore(); }
});

test("예약 대본: 4000자 이하, 필수 문구, 주입 문자 제거", () => {
  const s = reserveScript({ owner: "만일", place: "가명 식당", arrive: "18:30", party: 4 });
  assert.ok(s.length <= 4000);
  for (const t of ["'안녕하세요, AI 비서입니다. 만일님 대신 전화드렸어요. 오늘 오후 6시 30분에 네 명 예약 가능할까요? 주차도 되나요?'", "'그러세요'", "언제나 바로 대답합니다", "감사합니다", "직접 연락드리게 전해 드릴게요", "알겠습니다, 끊으셔도 됩니다", "협상하지 않고"])
    assert.ok(s.includes(t), t);
  assert.ok(reserveScript({ place: "x", arrive: "09:00", party: 1 }).includes("오늘 오전 9시에 한 명 예약")); // 정각은 분 생략
  assert.ok(reserveScript({ place: "x", arrive: "09:00", party: 1 }).includes("AI 비서입니다. 대신 전화드렸어요."));
  assert.ok(reserveScript({ place: "x", arrive: "00:05", party: 12 }).includes("오늘 오전 12시 5분에 열두 명"));
  assert.ok(reserveScript({ place: "x", arrive: "12:00", party: 20 }).includes("오늘 오후 12시에 스무 명"));
  const named = reserveScript({ owner: "만일", phone: "010-0000-1234", place: "x", arrive: "09:00", party: 1 });
  assert.ok(named.includes("'만일입니다'") && named.includes("'공, 일, 공, 공, 공, 공, 공, 일, 이, 삼, 사'"));
  assert.ok(!reserveScript({ place: "x", arrive: "09:00", party: 1 }).includes("연락처를 물으면")); // 번호 없으면 그전처럼 전해 드린다고만
  assert.ok(!reserveScript({ place: "가'\"`{}명", arrive: "09:00", party: 1 }).includes("{"));
  assert.ok(reserveScript({ owner: "만일", place: "가".repeat(40), arrive: "09:00", party: 1 }).length <= 4000);
});

test("결과 ok: 웹훅 → 행 status·summary·meta.parking 갱신, 번호는 가려서 요약으로", async () => {
  const f = fake({ memo: { status: "ok", summary: "18:30 4명 예약이 확정됐어요. 주차는 가능하대요.", parking: "가능" } });
  try {
    const env = mkEnv();
    await place(env);
    await hook(env, CID, "outbound");
    const [row] = rows(env);
    assert.deepEqual([row.status, row.summary], ["ok", "18:30 4명 예약이 확정됐어요. 주차는 가능하대요."]);
    assert.deepEqual(JSON.parse(row.meta), { party: 4, arrive: "18:30", parking: "가능" });
    const sent = to(f, "anthropic")[0].body;
    assert.ok(sent.system.includes("status는 가게가 예약을 확정했다고"));
    assert.ok(sent.messages[0].content.includes("상대: 네 가능합니다. 주차는 [번호] 앞에 하세요."));
    assert.equal(rows(env, "call").length, 0); // 손님 카드는 안 생김
  } finally { f.restore(); }
});

test("결과 need_manual: 가게 확정 아님·요약 실패·대화 없음·엉뚱한 값", async () => {
  const cases: [object | null, object[] | undefined, string, string][] = [
    [{ status: "need_manual", summary: "자리가 없다고 하셨어요.", parking: "불가" }, undefined, "자리가 없다고 하셨어요.", "불가"],
    [null, undefined, "직접 확인이 필요해요", "모름"], // 요약 실패
    [{ status: "ok", summary: "예약됐어요." }, [{ speaker: "s0", text: "AI 비서입니다" }], "직접 확인이 필요해요", "모름"], // 가게 말 없음
    [{ status: "yes", summary: "예약 010-0000-0000 확정", parking: "아마" }, undefined, "통화 내용을 정리하지 못했어요. 직접 확인해 주세요.", "모름"], // 가드·엉뚱한 값
  ];
  for (const [memo, segments, summary, parking] of cases) {
    const f = fake({ memo, segments });
    try {
      const env = mkEnv();
      await place(env);
      await hook(env, CID, "outbound");
      const [row] = rows(env);
      assert.deepEqual([row.status, row.summary, JSON.parse(row.meta).parking], ["need_manual", summary, parking]);
    } finally { f.restore(); }
  }
});

test("무응답·통화중·실패 상태 통지 → need_manual '전화를 받지 않았어요', 이미 끝난 행은 그대로", async () => {
  for (const body of ["CallId=" + CID + "&CallStatus=no-answer", "CallId=" + CID + "&Status=busy", JSON.stringify({ callId: CID, status: "failed" }), "CallId=" + CID + "&CallStatus=canceled"]) {
    const f = fake();
    try {
      const env = mkEnv();
      await place(env);
      assert.equal((await status(env, body)).status, 200);
      const [row] = rows(env);
      assert.deepEqual([row.status, row.summary], ["need_manual", "전화를 받지 않았어요"]);
      assert.equal(to(f, "/transcript").length, 0);
    } finally { f.restore(); }
  }
  const f = fake({ memo: { status: "ok", summary: "예약이 확정됐어요.", parking: "가능" } });
  try {
    const env = mkEnv();
    await place(env);
    await hook(env, CID, "outbound");
    await status(env, "CallId=" + CID + "&CallStatus=failed");
    assert.deepEqual([rows(env)[0].status, rows(env)[0].summary], ["ok", "예약이 확정됐어요."]);
  } finally { f.restore(); }
});

test("끝난 통화 상태 통지는 outbound도 받아쓰기를 요청한다", async () => {
  const f = fake();
  try {
    const env = mkEnv();
    await place(env);
    await status(env, "CallId=" + CID + "&CallStatus=completed&Direction=outbound-api");
    const t = to(f, "/transcript");
    assert.equal(t.length, 1);
    assert.equal(t[0].method, "POST");
    assert.equal(rows(env)[0].status, "calling"); // 결과는 transcript.completed 웹훅에서
  } finally { f.restore(); }
});

test("같은 웹훅이 두 번 와도 행은 한 번만 갱신(요약 호출도 한 번)", async () => {
  const f = fake({ memo: { status: "ok", summary: "예약이 확정됐어요.", parking: "가능" } });
  try {
    const env = mkEnv();
    await place(env);
    await hook(env, CID, "outbound");
    const before = rows(env)[0];
    f.calls.length = 0;
    await hook(env, CID, "outbound");
    assert.equal(f.calls.length, 0);
    assert.deepEqual(rows(env)[0], before);
    assert.equal(rows(env).length, 1);
  } finally { f.restore(); }
});

test("예약 행 없는 outbound 통화는 무시, inbound 카드 경로는 그대로", async () => {
  const f = fake({ memo: { caller: "김민수", summary: "견적 문의 전화예요.", callback_by: "" } });
  try {
    const env = mkEnv();
    await hook(env, CID, "outbound");
    assert.equal(f.calls.length, 0);
    await place(env);
    await hook(env, "CAffffffffffffffffffffffffffffffffffff", "outbound"); // 다른 통화
    assert.equal(rows(env)[0].status, "calling");
    await hook(env, "CA1111111111111111111111111111111111", "inbound", "01055556789");
    const c = rows(env, "call");
    assert.equal(c.length, 1);
    assert.deepEqual([c[0].status, c[0].from_label, c[0].summary], ["summarized", "김민수", "견적 문의 전화예요."]);
    assert.equal(rows(env)[0].status, "calling");
  } finally { f.restore(); }
});

test("차 폴링(/car/inbox)이 reserve 행을 그대로 내려 준다", async () => {
  const f = fake();
  try {
    const env = mkEnv();
    await place(env);
    const j = (await (await worker.fetch(new Request("https://x/car/inbox?since=0", { headers: { authorization: "Bearer t" } }), env)).json()) as any;
    const r = j.items.find((x: any) => x.kind === "reserve");
    assert.deepEqual([r.status, r.from_label, r.summary, JSON.parse(r.meta)], ["calling", "가명 식당", "18:30 4명 예약 전화 중", { party: 4, arrive: "18:30" }]);
  } finally { f.restore(); }
});

const dels = (st: ReturnType<typeof fake>) => st.calls.filter((c) => c.method === "DELETE");

test("요약 뒤 녹음 삭제: 예약 결과·수신 카드 모두 DELETE, keep_recordings면 안 지움, 삭제 실패해도 요약 저장", async () => {
  const f = fake({ memo: { status: "ok", summary: "예약이 확정됐어요.", parking: "가능" } });
  try {
    const env = mkEnv();
    await place(env);
    await hook(env, CID, "outbound");
    assert.equal(dels(f).length, 1);
    assert.equal(dels(f)[0].url, `https://api.claw-ops.com/v1/accounts/b/recordings/${CID}`);
    await hook(env, CID, "outbound"); // 재전송은 다시 지우지 않는다
    assert.equal(dels(f).length, 1);
    await hook(env, "CA1111111111111111111111111111111111", "inbound", "01055556789");
    assert.equal(dels(f).length, 2);
    assert.match(dels(f)[1].url, /\/recordings\/CA1111111111111111111111111111111111$/);
  } finally { f.restore(); }
  const g = fake({ memo: { status: "ok", summary: "예약이 확정됐어요.", parking: "가능" } });
  try {
    const env = mkEnv();
    env.DB.db.prepare("INSERT INTO kv(key,value,updated_at) VALUES('keep_recordings','true',0)").run();
    await place(env);
    await hook(env, CID, "outbound");
    assert.equal(rows(env)[0].status, "ok");
    assert.equal(dels(g).length, 0);
  } finally { g.restore(); }
  const h = fake({ memo: { status: "ok", summary: "예약이 확정됐어요.", parking: "가능" } });
  const inner = globalThis.fetch;
  globalThis.fetch = (async (u: string, init: any = {}) => { if (init.method === "DELETE") throw new Error("boom"); return inner(u, init); }) as unknown as typeof fetch;
  try {
    const env = mkEnv();
    await place(env);
    await hook(env, CID, "outbound");
    assert.deepEqual([rows(env)[0].status, rows(env)[0].summary], ["ok", "예약이 확정됐어요."]);
  } finally { h.restore(); }
});

test("요약 실패면 녹음을 지우지 않는다: 예약·수신 모두", async () => {
  const f = fake({ memo: null });
  try {
    const env = mkEnv();
    await place(env);
    await hook(env, CID, "outbound");
    assert.equal(rows(env)[0].status, "need_manual");
    await hook(env, "CA1111111111111111111111111111111111", "inbound", "01055556789");
    assert.equal(dels(f).length, 0);
  } finally { f.restore(); }
});

test("예약 전화 하루 5통 상한: 오늘(KST) 5건이면 발신 없이 daily_limit, 어제 행은 안 센다", async () => {
  const f = fake();
  try {
    const env = mkEnv();
    const add = (t: number) => env.DB.db.prepare("INSERT INTO inbox(kind,status,call_id,from_label,summary,created_at) VALUES('reserve','ok','x','x','x',?)").run(t);
    const day0 = Math.floor((Date.now() + 9 * 3_600_000) / 86_400_000) * 86_400_000 - 9 * 3_600_000;
    for (let i = 0; i < 5; i++) add(day0 - 1000); // 어제 23:59(KST)
    assert.equal(((await (await reserve(env, OK)).json()) as any).ok, true); // 어제 5건은 무관
    f.calls.length = 0;
    for (let i = 0; i < 3; i++) add(day0 + 1000); // 오늘 1(위 발신)+3 = 4
    assert.equal(((await (await reserve(env, OK)).json()) as any).ok, true); // 5번째
    f.calls.length = 0;
    assert.deepEqual(await (await reserve(env, OK)).json(), { ok: false, code: "daily_limit" });
    assert.equal(f.calls.length, 0);
  } finally { f.restore(); }
});

// --- 주차 문의 전화(purpose=parking) ---
const PK = { place: "가명 주차장", purpose: "parking" };

test("주차 문의: 시간·인원 없이 통과, 대사는 자리·주차비만, 예약 문장 없음, 행 meta.purpose", async () => {
  const f = fake();
  try {
    const env = mkEnv();
    const r = (await (await reserve(env, PK)).json()) as any;
    assert.equal(r.ok, true);
    const ins = to(f, "/calls")[0].body.CallContext.Instruction as string;
    assert.match(ins, /지금 주차 자리가 있을까요\? 주차비는 얼마인가요\?/);
    assert.ok(!ins.includes("예약 가능할까요") && !ins.includes("몇 명"));
    assert.ok(ins.includes("AI 비서입니다"));
    const [row] = rows(env);
    assert.deepEqual([row.kind, row.status, row.summary], ["reserve", "calling", "주차 문의 전화 중"]);
    assert.deepEqual(JSON.parse(row.meta), { purpose: "parking" });
    assert.equal((await reserve(env, { ...PK, place: "" })).status, 400); // 장소 검증은 그대로
    assert.equal((await reserve(env, { ...OK, purpose: "x" })).status, 200); // 모르는 purpose = 예약
    assert.equal((await reserve(env, { place: "가명 식당", purpose: "x" })).status, 400);
  } finally { f.restore(); }
});

test("주차 문의도 하루 상한에 합산, O4_LIVE 없으면 OWNER_NUMBER로만", async () => {
  const f = fake({ kakao: [{ phone: SHOP, place_name: "가명 주차장" }] });
  try {
    const env = mkEnv({ KAKAO_REST_KEY: "kk" });
    for (let i = 0; i < 3; i++) assert.equal(((await (await reserve(env, PK)).json()) as any).ok, true);
    for (let i = 0; i < 2; i++) assert.equal(((await (await reserve(env, OK)).json()) as any).ok, true);
    assert.deepEqual(await (await reserve(env, PK)).json(), { ok: false, code: "daily_limit" });
    assert.deepEqual(await (await reserve(env, OK)).json(), { ok: false, code: "daily_limit" });
    assert.equal(to(f, "kakao").length, 0);
    assert.ok(to(f, "/calls").every((c) => c.body.To === OWNER));
    assert.equal(to(f, "/calls").length, 5);
  } finally { f.restore(); }
});

test("주차 문의 결과: PARKING_SYSTEM으로 요약(예약 시스템 문장 아님), 예약 행은 RESERVE_SYSTEM 그대로", async () => {
  let f = fake({ memo: { status: "ok", summary: "지금 주차 자리가 있고 한 시간에 삼천 원이래요.", parking: "가능" } });
  try {
    const env = mkEnv();
    await reserve(env, PK);
    await hook(env, CID, "outbound");
    const [row] = rows(env);
    assert.deepEqual([row.status, row.summary], ["ok", "지금 주차 자리가 있고 한 시간에 삼천 원이래요."]);
    assert.deepEqual(JSON.parse(row.meta), { purpose: "parking", parking: "가능" });
    const sys = to(f, "anthropic")[0].body.system as string;
    assert.ok(sys.includes("지금 주차 자리가 있다고 분명히 답했을 때만 ok"));
    assert.ok(!sys.includes("예약을 확정했다고"));
  } finally { f.restore(); }
  f = fake({ memo: { status: "ok", summary: "예약됐어요.", parking: "가능" } });
  try {
    const env = mkEnv();
    await place(env);
    await hook(env, CID, "outbound");
    const sys = to(f, "anthropic")[0].body.system as string;
    assert.ok(sys.includes("status는 가게가 예약을 확정했다고") && !sys.includes("주차 문의"));
  } finally { f.restore(); }
});

test("예약 대본은 그대로, 주차 대본은 별개 함수", () => {
  const s = reserveScript({ owner: "만일", place: "가명 식당", arrive: "18:30", party: 4 });
  assert.ok(s.includes("오늘 오후 6시 30분에 네 명 예약 가능할까요? 주차도 되나요?"));
  assert.ok(s.includes("알아낼 것은 오늘 오후 6시 30분에 네 명 예약이 되는지, 주차가 되는지 두 가지입니다."));
  const p = parkingScript({ owner: "만일", place: "가'\"`{}명" });
  assert.ok(p.includes("만일님 대신 전화드렸어요. 지금 주차 자리가 있을까요?") && !p.includes("{") && p.length <= 4000);
});

// --- 공개 APK 토큰 남용 방지: 문자 발송·AI 대화 하루 상한 ---
const send = (env: any, to_number: string) =>
  worker.fetch(new Request("https://x/car/send", { method: "POST", headers: { authorization: "Bearer t" }, body: JSON.stringify({ to_number, body: "가명 문자", confirmed: true }) }), env);
const chat = (env: any) =>
  worker.fetch(new Request("https://x/car/chat", { method: "POST", headers: { authorization: "Bearer t" }, body: JSON.stringify({ text: "안녕" }) }), env);

test("문자 발송 하루 20건 상한: 21번째는 429 daily_limit, 중계 호출 없음", async () => {
  const f = fake();
  try {
    const env = mkEnv({ SMS_RELAY_URL: "https://relay.test/send" });
    for (let i = 0; i < 20; i++) assert.equal((await send(env, "010-1111-2222")).status, 200);
    f.calls.length = 0;
    const r = await send(env, "010-1111-2222");
    assert.equal(r.status, 429);
    assert.deepEqual(await r.json(), { ok: false, code: "daily_limit" });
    assert.equal(f.calls.length, 0);
  } finally { f.restore(); }
});

test("데모 번호(010-0000-000x) 발송은 하루 상한에 안 센다", async () => {
  const f = fake();
  try {
    const env = mkEnv({ SMS_RELAY_URL: "https://relay.test/send" });
    for (let i = 0; i < 25; i++) assert.equal((await send(env, "010-0000-0001")).status, 200);
    assert.equal((await send(env, "010-1111-2222")).status, 200); // 실제 번호는 아직 0건
    assert.equal(rows(env, "sent").length, 26);
  } finally { f.restore(); }
});

test("말동무 하루 200회 상한: 넘으면 AI fetch 없이 FALLBACK.chat, 어제 횟수는 무관", async () => {
  const f = fake();
  try {
    const env = mkEnv();
    const day = new Date(Date.now() + 9 * 3_600_000).toISOString().slice(0, 10);
    const put = (v: object) => env.DB.db.prepare("INSERT OR REPLACE INTO kv(key,value,updated_at) VALUES('chat_count',?,?)").run(JSON.stringify(v), Date.now());
    put({ day: "2000-01-01", n: 999 }); // 어제 이전 횟수는 안 센다
    await chat(env);
    assert.equal(to(f, "anthropic").length, 1); // 상한 전에는 AI를 부른다
    put({ day, n: 200 });
    f.calls.length = 0;
    assert.deepEqual(await (await chat(env)).json(), { reply: FALLBACK.chat, source: "fallback" });
    assert.equal(to(f, "anthropic").length, 0);
  } finally { f.restore(); }
});
