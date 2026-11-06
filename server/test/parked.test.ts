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
  assert.deepEqual(await (await post(env, { lat: 37.4, lng: "127.1", memo: "B2 3열", tasks: ["약국"] })).json(), { ok: true });
  const p = await get(env);
  assert.deepEqual([p.lat, p.lng, p.address, p.memo, p.tasks], [37.4, 127.1, "가명로 1", "B2 3열", ["약국"]]);
  assert.ok(Number.isFinite(p.at));
  await post(env, { lat: 35, lng: 129 });
  const q = await get(env);
  assert.deepEqual([q.lat, q.memo, q.tasks], [35, "", []]); // 마지막 1건만
  assert.deepEqual(await (await post(env, { clear: true })).json(), { ok: true });
  assert.equal(await get(env), null);
});

test("좌표 잘못됨 400(저장 안 함), memo 40자·tasks 3개·80자로 자름", async () => {
  const env = mkEnv();
  for (const bad of [{}, { lat: "x", lng: 1 }, { lat: 1 }, { lat: null, lng: 1 }, { lat: "", lng: 1 }, { lat: Infinity, lng: 1 }, { lat: 1, lng: {} }, "bad"])
    assert.equal((await post(env, bad)).status, 400, JSON.stringify(bad));
  assert.equal(await get(env), null);
  assert.deepEqual(await (await post(env, {})).json(), { ok: false });
  await post(env, { lat: 1, lng: 2, memo: "가".repeat(60), tasks: ["a".repeat(100), 5, " ", "b", "c", "d"] });
  const p = await get(env);
  assert.equal(p.memo.length, 40);
  assert.deepEqual(p.tasks, ["a".repeat(80), "b", "c"]);
});

test("주소 조회 실패해도 저장은 한다(빈 주소), 키 없어도 마찬가지", async () => {
  mock([], false);
  const env = mkEnv({ KAKAO_REST_KEY: "kk" });
  assert.equal((await post(env, { lat: 1, lng: 2 })).status, 200);
  assert.equal((await get(env)).address, "");
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

test("공공 주차 뼈대: 키 없으면 no_key", async () => {
  assert.deepEqual(await (await call(mkEnv(), "GET", "/car/parking?lat=1&lng=2")).json(), { reason: "no_key" });
});
