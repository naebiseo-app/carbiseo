// 이 테스트가 지키는 동작:
// - 차 경로 인증: CAR_TOKEN이 비어 있으면 'Bearer undefined'도 거절하고, 틀린 토큰은 401이다.
// - forCar가 ring·event 행의 원문(raw·meta)을 차로 내리지 않는다.
// - loadDialog의 재시도(한 번)와 /admin POST의 다른 출처 거절(403)을 확인한다.
import test from "node:test";
import assert from "node:assert/strict";
import worker, { forCar, loadDialog } from "../src/index.ts";

// 최소 D1 흉내: 빈 표
const db = {
  prepare() {
    const st = { bind() { return st; }, async run() { return { meta: { changes: 1 } }; }, async first() { return null; }, async all() { return { results: [] }; } };
    return st;
  },
};
const mkEnv = (over: Record<string, unknown> = {}) => ({ DB: db, CAR_TOKEN: "t", CALL_CONTEXT_SECRET: "cc", ADMIN_TOKEN: "adm", ...over }) as any;
const req = (env: any, method: string, path: string, headers: Record<string, string> = {}, body?: BodyInit) =>
  worker.fetch(new Request("https://x" + path, { method, headers, body }), env);

test("CAR_TOKEN이 비어 있으면 'Bearer undefined'도 401", async () => {
  const env = mkEnv({ CAR_TOKEN: undefined });
  assert.equal((await req(env, "GET", "/car/inbox", { authorization: "Bearer undefined" })).status, 401);
  assert.equal((await req(mkEnv({ CAR_TOKEN: "" }), "GET", "/car/inbox", { authorization: "Bearer " })).status, 401);
});

test("차 경로: 인증 없음·틀린 토큰은 401, 맞는 토큰은 통과", async () => {
  const env = mkEnv();
  for (const [m, p] of [["GET", "/car/inbox"], ["POST", "/car/state"], ["POST", "/car/send"]]) {
    assert.equal((await req(env, m, p)).status, 401, `${m} ${p} 헤더 없음`);
    assert.equal((await req(env, m, p, { authorization: "Bearer wrong" })).status, 401, `${m} ${p} 틀린 토큰`);
  }
  assert.equal((await req(env, "GET", "/car/inbox", { authorization: "Bearer t" })).status, 200);
});

test("forCar: ring·event는 raw·meta를 비우고 call은 그대로", () => {
  const rows = [
    { id: 1, kind: "ring", raw: "From=01000000000", meta: '{"sig":"x"}', summary: "s" },
    { id: 2, kind: "event", raw: "서명", meta: "{}", status: "received" },
    { id: 3, kind: "call", raw: "r", meta: "m", summary: "c" },
  ];
  const out = forCar(rows) as any[];
  assert.deepEqual(out[0], { id: 1, kind: "ring", raw: null, meta: null, summary: "s" });
  assert.deepEqual(out[1], { id: 2, kind: "event", raw: null, meta: null, status: "received" });
  assert.deepEqual(out[2], rows[2]);
  assert.equal(rows[0].raw, "From=01000000000", "원본 행은 바꾸지 않는다");
});

test("loadDialog: 500 두 번이면 reject(호출 2번), 첫 500·둘째 200이면 대화", async () => {
  const env = mkEnv({ CLAWOPS_ACCOUNT_ID: "a", CLAWOPS_API_KEY: "k" });
  const orig = globalThis.fetch;
  try {
    let n = 0;
    globalThis.fetch = (async () => { n++; return new Response("err", { status: 500 }); }) as typeof fetch;
    await assert.rejects(() => loadDialog(env, "CA1"), /transcript 500/);
    assert.equal(n, 2);

    n = 0;
    const segs = { segments: [{ speaker: "A", text: "AI 비서입니다" }, { speaker: "B", text: "김민수입니다 01000000000 로 주세요" }] };
    globalThis.fetch = (async () => { n++; return n === 1 ? new Response("err", { status: 500 }) : Response.json(segs); }) as typeof fetch;
    const r = await loadDialog(env, "CA1");
    assert.equal(n, 2);
    assert.equal(r.said, "김민수입니다 01000000000 로 주세요");
    assert.equal(r.dialog, "비서: AI 비서입니다\n상대: 김민수입니다 [번호] 로 주세요");
  } finally { globalThis.fetch = orig; }
});

test("loadDialog: 200이면 한 번만 부르고, aiSays가 든 화자를 AI로", async () => {
  const env = mkEnv({ CLAWOPS_ACCOUNT_ID: "a", CLAWOPS_API_KEY: "k" });
  const orig = globalThis.fetch;
  try {
    let n = 0;
    const segs = { segments: [{ speaker: "B", text: "여보세요" }, { speaker: "A", text: "AI 비서입니다" }] };
    globalThis.fetch = (async () => { n++; return Response.json(segs); }) as typeof fetch;
    const r = await loadDialog(env, "CA1", "AI 비서");
    assert.equal(n, 1);
    assert.equal(r.said, "여보세요");
  } finally { globalThis.fetch = orig; }
});

test("/admin POST: 다른 출처는 403, Origin 없거나 같은 출처는 통과", async () => {
  const env = mkEnv();
  const auth = { authorization: "Basic " + btoa("a:adm") };
  const post = (h: Record<string, string>) => req(env, "POST", "/admin/owner-as-guest", { ...auth, ...h }, new URLSearchParams({ on: "1" }));
  assert.equal((await post({ origin: "https://evil.example" })).status, 403);
  assert.notEqual((await post({})).status, 403);
  assert.notEqual((await post({ origin: "https://x" })).status, 403);
  // 인증이 틀리면 출처와 무관하게 401(기존 동작)
  assert.equal((await req(env, "POST", "/admin/owner-as-guest", { origin: "https://evil.example" })).status, 401);
});
