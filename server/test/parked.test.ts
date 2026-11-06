import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker, { coordAddress } from "../src/index.ts";

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
const mkEnv = (more: object = {}) => ({ DB: d1(), CAR_TOKEN: "t", CALL_CONTEXT_SECRET: "cc", ...more }) as any;
const call = (env: any, method: string, path: string, body?: unknown, auth = "Bearer t") =>
  worker.fetch(new Request("https://x" + path, { method, headers: { authorization: auth }, body: body === undefined ? undefined : JSON.stringify(body) }), env);
const post = (env: any, body: unknown, auth?: string) => call(env, "POST", "/car/parked", body, auth);
const get = async (env: any) => ((await (await call(env, "GET", "/phone/parked")).json()) as any).parked;

const real = globalThis.fetch;
const mock = (documents: unknown[], ok = true) => { globalThis.fetch = (async () => ({ ok, json: async () => ({ documents }) })) as unknown as typeof fetch; };
test.afterEach(() => { globalThis.fetch = real; });

test("coordAddress: 도로명 우선, 없으면 지번, 키 없음·실패는 빈 문자열", async () => {
  mock([{ road_address: { address_name: "가명로 1" }, address: { address_name: "가명동 2" } }]);
  assert.equal(await coordAddress("k", 37.4, 127.1), "가명로 1");
  mock([{ road_address: null, address: { address_name: "가명동 2" } }]);
  assert.equal(await coordAddress("k", 37.4, 127.1), "가명동 2");
  mock([], false); assert.equal(await coordAddress("k", 37.4, 127.1), "");
  globalThis.fetch = (async () => { throw new Error("x"); }) as unknown as typeof fetch;
  assert.equal(await coordAddress("k", 37.4, 127.1), "");
  assert.equal(await coordAddress(undefined, 37.4, 127.1), "");
});

test("저장·덮어쓰기·clear, 주소는 카카오 좌표→주소", async () => {
  mock([{ road_address: { address_name: "가명로 1" }, address: null }]);
  const env = mkEnv({ KAKAO_REST_KEY: "kk" });
  assert.equal(await get(env), null);
  assert.deepEqual(await (await post(env, { lat: 37.4, lng: "127.1", memo: "B2 3열", tasks: [{ id: -1, title: "약국" }] })).json(), { ok: true, address: "가명로 1" });
  const p = await get(env);
  assert.deepEqual([p.lat, p.lng, p.address, p.memo, p.tasks], [37.4, 127.1, "가명로 1", "B2 3열", [{ id: -1, title: "약국", sub: "", number: null }]]);
  assert.ok(Number.isFinite(p.at));
  await post(env, { lat: 35, lng: 129 });
  const q = await get(env);
  assert.deepEqual([q.lat, q.memo, q.tasks], [35, "", []]); // 마지막 1건만
  assert.deepEqual(await (await post(env, { clear: true })).json(), { ok: true });
  assert.equal(await get(env), null);
});

test("좌표 잘못됨 400(저장 안 함), memo 40자·tasks 8개·80자로 자르고 잘못된 항목은 버림", async () => {
  const env = mkEnv();
  for (const bad of [{}, { lat: "x", lng: 1 }, { lat: 1 }, { lat: null, lng: 1 }, { lat: "", lng: 1 }, { lat: Infinity, lng: 1 }, { lat: 1, lng: {} }, "bad"])
    assert.equal((await post(env, bad)).status, 400, JSON.stringify(bad));
  assert.equal(await get(env), null);
  assert.deepEqual(await (await post(env, {})).json(), { ok: false });
  await post(env, { lat: 1, lng: 2, memo: "가".repeat(60), tasks: [{ id: 1, title: "a".repeat(100) }, 5, "x", { id: 1.5, title: "x" }, { id: 2, title: " " }, ...[3, 4, 5, 6, 7, 8, 9, 10].map((id) => ({ id, title: "t" + id }))] });
  const p = await get(env);
  assert.equal(p.memo.length, 40);
  assert.equal(p.tasks.length, 8);
  assert.deepEqual([p.tasks[0].title.length, p.tasks[1].id], [80, 3]);
});

test("total: 자르기 전 건수를 저장하고 폰에서 끝낸 만큼 빼며, sub는 60자로 자른다", async () => {
  const env = mkEnv();
  const ts = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((id) => ({ id, title: "t" + id, sub: id === 1 ? "나".repeat(70) : "오전 10:20 통화에서 할 일" }));
  await post(env, { lat: 1, lng: 2, tasks: ts, total: 12 });
  let p = await get(env);
  assert.deepEqual([p.tasks.length, p.total, p.tasks[0].sub.length, p.tasks[1].sub], [8, 12, 60, "오전 10:20 통화에서 할 일"]);
  await doneCall(env, 3); await doneCall(env, 3); await doneCall(env, 999); // 중복·목록 밖 id는 한 번만/세지 않음
  p = await get(env);
  assert.deepEqual([p.tasks.length, p.total], [7, 11]);
  await post(env, { lat: 1, lng: 2, tasks: ts.slice(0, 2) }); // total 없음 → 건수 그대로, 이미 끝낸 3은 목록에 없으니 안 뺀다
  assert.equal((await get(env)).total, 2);
  await post(env, { lat: 1, lng: 2, tasks: ts.slice(0, 3), total: 1 }); // total이 목록보다 작으면 목록 건수로
  assert.equal((await get(env)).total, 2); // 3은 끝낸 일이라 빠짐
});

test("주소 조회 실패해도 저장은 한다(빈 주소), 키 없어도 마찬가지", async () => {
  mock([], false);
  const env = mkEnv({ KAKAO_REST_KEY: "kk" });
  assert.equal((await post(env, { lat: 1, lng: 2 })).status, 200);
  assert.equal((await get(env)).address, "");
  assert.deepEqual(await (await post(env, { lat: 1, lng: 2 })).json(), { ok: true, address: null });
  const env2 = mkEnv();
  assert.equal((await post(env2, { lat: 1, lng: 2 })).status, 200);
  assert.equal((await get(env2)).address, "");
});

test("인증 없으면 401: 저장·폰 조회·공공 주차", async () => {
  const env = mkEnv();
  assert.equal((await post(env, { lat: 1, lng: 2 }, "Bearer x")).status, 401);
  assert.equal((await post(env, { clear: true }, "")).status, 401);
  assert.equal((await call(env, "GET", "/phone/parked", undefined, "Bearer x")).status, 401);
  assert.equal((await call(env, "GET", "/car/parking?lat=1&lng=2", undefined, "Bearer x")).status, 401);
  assert.equal(env.DB.db.prepare("SELECT 1 FROM kv WHERE key='parked'").get(), undefined);
});

const doneCall = (env: any, id: unknown, auth?: string) => call(env, "POST", "/phone/parked/done", { id }, auth);
const addRow = (env: any, num: string | null) => Number(env.DB.db.prepare("INSERT INTO inbox(kind,status,from_number,from_label) VALUES('task','open',?,?)").run(num, "김민수").lastInsertRowid);

test("폰 조회: 서버 행 id는 발신 번호를 붙이고 kv에는 번호를 저장하지 않음, 로컬 id는 null", async () => {
  const env = mkEnv();
  const a = addRow(env, "010-0000-0000"), b = addRow(env, null);
  await post(env, { lat: 1, lng: 2, tasks: [{ id: a, title: "김민수에게 답장" }, { id: b, title: "이지은 확인" }, { id: -3, title: "약국" }] });
  assert.deepEqual((await get(env)).tasks.map((t: any) => t.number), ["010-0000-0000", null, null]);
  assert.ok(!(env.DB.db.prepare("SELECT value FROM kv WHERE key='parked'").get() as any).value.includes("010-0000-0000"));
});

test("폰에서 끝냄: 조회에서 빠지고, 차 GET에 id, 서버 행 done, 재전송해도 유지, 로컬 id는 행 건드리지 않음", async () => {
  const env = mkEnv();
  const a = addRow(env, "010-0000-0000"), b = addRow(env, null);
  await post(env, { lat: 1, lng: 2, tasks: [{ id: a, title: "A" }, { id: b, title: "B" }, { id: -1, title: "C" }] });
  assert.deepEqual(await (await call(env, "GET", "/car/parked")).json(), { done: [] });
  assert.deepEqual(await (await doneCall(env, a)).json(), { ok: true });
  await doneCall(env, -1); await doneCall(env, a);
  assert.deepEqual((await get(env)).tasks.map((t: any) => t.id), [b]);
  assert.deepEqual(await (await call(env, "GET", "/car/parked")).json(), { done: [a, -1] });
  assert.equal((env.DB.db.prepare("SELECT status FROM inbox WHERE id=?").get(a) as any).status, "done");
  assert.equal((env.DB.db.prepare("SELECT status FROM inbox WHERE id=?").get(b) as any).status, "open");
  await post(env, { lat: 1, lng: 2, memo: "m", tasks: [{ id: a, title: "A" }, { id: b, title: "B" }] });
  assert.deepEqual((await get(env)).tasks.map((t: any) => t.id), [b]);
  await post(env, { clear: true });
  assert.deepEqual(await (await call(env, "GET", "/car/parked")).json(), { done: [] });
  assert.equal((await doneCall(env, "x")).status, 400);
  assert.equal((await doneCall(env, 1.5)).status, 400);
});

test("done·GET /car/parked 인증 없으면 401", async () => {
  const env = mkEnv();
  assert.equal((await doneCall(env, 1, "Bearer x")).status, 401);
  assert.equal((await call(env, "GET", "/car/parked", undefined, "Bearer x")).status, 401);
});

test("공공 주차 뼈대: 키 없으면 no_key", async () => {
  assert.deepEqual(await (await call(mkEnv(), "GET", "/car/parking?lat=1&lng=2")).json(), { reason: "no_key" });
});
