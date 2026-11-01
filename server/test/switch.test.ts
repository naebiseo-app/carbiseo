import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.ts";

// 최소 D1 흉내: kv 표만 기억하고 실행한 SQL을 남긴다.
function fakeDB() {
  const kv: Record<string, string> = {};
  const log: string[] = [];
  return {
    kv, log,
    prepare(sql: string) {
      let args: unknown[] = [];
      const st = {
        bind(...a: unknown[]) { args = a; return st; },
        async run() {
          log.push(sql);
          const k = sql.match(/VALUES\('(\w+)',\?/)?.[1];
          if (k) kv[k] = args[0] as string;
          return { meta: { changes: sql.startsWith("DELETE FROM inbox") ? 3 : 1 } };
        },
        async first() { const v = sql.includes("key=?") ? kv[args[0] as string] : undefined; return v == null ? null : { value: v }; },
        async all() { return { results: [] }; },
      };
      return st;
    },
  };
}

const OWNER = "01011112222"; // 가상 번호
const mkEnv = () => ({ DB: fakeDB(), CAR_TOKEN: "t", CALL_CONTEXT_SECRET: "cc", ADMIN_TOKEN: "adm", OWNER_NUMBER: OWNER }) as any;
const admin = (env: any, path: string, form: Record<string, string> = {}, pw = "adm") =>
  worker.fetch(new Request("https://x" + path, { method: "POST", headers: { authorization: "Basic " + btoa("a:" + pw) }, body: new URLSearchParams(form) }), env);
const ring = async (env: any) =>
  ((await (await worker.fetch(new Request("https://x/clawops/call-context/cc", { method: "POST", body: `From=${OWNER}` }), env)).json()) as any).CallContext;

test("녹화 스위치: 기본 꺼짐 → 주인 대본, 켜면 손님 대본, 끄면 다시 주인", async () => {
  const env = mkEnv();
  assert.equal((await ring(env)).Variables.owner_mode, "1");
  assert.equal((await admin(env, "/admin/owner-as-guest", { on: "1" })).status, 200);
  const c = await ring(env);
  assert.equal(c.Variables.owner_mode, undefined);
  assert.doesNotMatch(c.Instruction, /본인/);
  await admin(env, "/admin/owner-as-guest", { on: "0" });
  assert.equal((await ring(env)).Variables.owner_mode, "1");
});

test("관리자 인증: 비밀번호 틀리면 스위치·inbox 비우기 401", async () => {
  const env = mkEnv();
  assert.equal((await admin(env, "/admin/owner-as-guest", { on: "1" }, "no")).status, 401);
  assert.equal((await admin(env, "/admin/reset-inbox", {}, "no")).status, 401);
  assert.equal(env.DB.kv.owner_as_guest, undefined);
  assert.ok(!env.DB.log.includes("DELETE FROM inbox"));
});

test("/admin/reset-inbox: inbox 전체 삭제", async () => {
  const env = mkEnv();
  const r = await admin(env, "/admin/reset-inbox");
  assert.equal(r.status, 200);
  assert.ok(env.DB.log.includes("DELETE FROM inbox"));
  assert.match(await r.text(), /3건을 비웠습니다/);
});

test("/car/inbox: 비었으면 min_id null(차가 카드를 비운다)", async () => {
  const env = mkEnv();
  const r = await worker.fetch(new Request("https://x/car/inbox?since=9", { headers: { authorization: "Bearer t" } }), env);
  assert.deepEqual(await r.json(), { items: [], min_id: null, more: false });
});

test("/admin/demo-clock: 시연 시각만큼 offset 저장, none이면 0", async () => {
  const env = mkEnv();
  await admin(env, "/admin/demo-clock", { at: "2030-01-07T10:30" });
  const want = Date.parse("2030-01-07T10:30:00+09:00") - Date.now();
  assert.ok(Math.abs(Number(env.DB.kv.demo_offset) - want) < 5000);
  await admin(env, "/admin/demo-clock", { at: "none" });
  assert.equal(env.DB.kv.demo_offset, "0");
});
