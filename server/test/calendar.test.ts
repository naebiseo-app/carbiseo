import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker from "../src/index.ts";
import { toEvents } from "../src/calendar.ts";

function d1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));
  return {
    prepare(sql: string) {
      let args: any[] = [];
      const st = {
        bind(...a: any[]) { args = a; return st; },
        async run() { const r = db.prepare(sql).run(...args); return { meta: { changes: Number(r.changes) } }; },
        async first() { return db.prepare(sql).get(...args) ?? null; },
        async all() { return { results: db.prepare(sql).all(...args) }; },
      };
      return st;
    },
  };
}
const mkEnv = (more: object = {}) => ({ DB: d1(), CAR_TOKEN: "t", CALL_CONTEXT_SECRET: "cc", GOOGLE_CLIENT_SECRET: "sec", ...more }) as any;
const call = (env: any, method: string, path: string, body?: unknown, auth = "Bearer t") =>
  worker.fetch(new Request("https://x" + path, { method, headers: { authorization: auth }, body: body === undefined ? undefined : JSON.stringify(body) }), env);
const kv = async (env: any, k: string) => { const r = await env.DB.prepare("SELECT value FROM kv WHERE key=?").bind(k).first(); return r ? JSON.parse(r.value) : null; };
const stale = (env: any, at = 0) => env.DB.prepare("UPDATE kv SET value=json_set(value,'$.tried_at',?) WHERE key='google'").bind(at).run();

const real = globalThis.fetch;
test.afterEach(() => { globalThis.fetch = real; });

// 구글 가짜: 토큰·일정 응답을 바꿔 끼우고 호출 기록을 남긴다
function google(o: { token?: { status: number; body: any }; events?: { status: number; items?: any[] } } = {}) {
  const calls: { url: string; body: string }[] = [];
  globalThis.fetch = (async (url: any, init: any) => {
    const u = String(url); const b = String(init?.body ?? ""); calls.push({ url: u, body: b });
    const res = (status: number, body: any) => ({ ok: status < 300, status, json: async () => body });
    if (u.includes("/revoke")) return res(200, {});
    if (u.includes("/token")) {
      if (b.includes("authorization_code")) return res(200, { refresh_token: "RT", access_token: "AT" });
      const t = o.token ?? { status: 200, body: { access_token: "AT" } };
      return res(t.status, t.body);
    }
    const e = o.events ?? { status: 200, items: [] };
    return res(e.status, { items: e.items });
  }) as unknown as typeof fetch;
  return calls;
}
const EV = [
  { summary: "거래처 미팅", location: "판교역", start: { dateTime: "2026-11-08T10:00:00+09:00" } },
  { status: "cancelled", summary: "취소됨", start: { dateTime: "2026-11-08T11:00:00+09:00" } },
  { location: "집", start: { date: "2026-11-09" } },
];

test("toEvents: 제목·장소·시작, 종일은 KST 자정, 취소 제외, 제목 없으면 일정", () => {
  const r = toEvents(EV);
  assert.equal(r.length, 2);
  assert.deepEqual([r[0].title, r[0].place, r[0].start, r[0].contact], ["거래처 미팅", "판교역", Date.parse("2026-11-08T01:00:00Z"), ""]);
  assert.deepEqual([r[1].title, r[1].start], ["일정", Date.parse("2026-11-08T15:00:00Z")]);
});

test("연결: 코드 교환 후 refresh_token 저장, 즉시 동기화, 토큰은 응답에 없다", async () => {
  const calls = google({ events: { status: 200, items: EV } });
  const env = mkEnv();
  assert.deepEqual(await (await call(env, "GET", "/phone/google")).json(), { connected: false, synced_at: null, expired: false });
  const res = await call(env, "POST", "/phone/google", { code: "abc" });
  const txt = await res.text();
  assert.equal(res.status, 200);
  assert.ok(!txt.includes("RT") && !txt.includes("AT"));
  const p = new URLSearchParams(calls[0].body);
  assert.deepEqual([p.get("grant_type"), p.get("code"), p.get("redirect_uri"), p.get("client_secret")], ["authorization_code", "abc", "", "sec"]);
  assert.equal((await kv(env, "schedule")).length, 2);
  const st = (await (await call(env, "GET", "/phone/google")).json()) as any;
  assert.equal(st.connected, true);
  assert.ok(st.synced_at > 0);
  assert.ok(calls.some((c) => c.url.includes("calendars/primary/events") && c.url.includes("singleEvents=true") && c.url.includes("maxResults=20")));
});

test("인증 필요, secret 없으면 503, 교환 실패와 refresh_token 없음은 400", async () => {
  google();
  assert.equal((await call(mkEnv(), "POST", "/phone/google", { code: "a" }, "Bearer x")).status, 401);
  const r = await call(mkEnv({ GOOGLE_CLIENT_SECRET: undefined }), "POST", "/phone/google", { code: "a" });
  assert.deepEqual([r.status, await r.json()], [503, { error: "no_secret" }]);
  globalThis.fetch = (async () => ({ ok: true, json: async () => ({ access_token: "AT" }) })) as unknown as typeof fetch; // refresh_token 없음
  const env = mkEnv();
  assert.equal((await call(env, "POST", "/phone/google", { code: "a" })).status, 400);
  assert.equal(await kv(env, "google"), null);
  globalThis.fetch = (async () => ({ ok: false, json: async () => ({ error: "invalid_grant" }) })) as unknown as typeof fetch;
  assert.equal((await call(env, "POST", "/phone/google", { code: "a" })).status, 400);
});

test("동기화 실패는 기존 일정을 유지하고 응답을 막지 않는다", async () => {
  google({ events: { status: 200, items: EV } });
  const env = mkEnv();
  await call(env, "POST", "/phone/google", { code: "a" });
  const before = await kv(env, "schedule");
  const synced = (await kv(env, "google")).synced_at;
  google({ events: { status: 500 } });
  await stale(env);
  const r = await call(env, "GET", "/car/schedule");
  assert.equal(r.status, 200);
  assert.deepEqual(((await r.json()) as any).items, before);
  assert.equal((await kv(env, "google")).synced_at, synced);
  globalThis.fetch = (async () => { throw new Error("net"); }) as unknown as typeof fetch;
  await stale(env);
  assert.equal((await call(env, "GET", "/car/schedule")).status, 200);
});

test("invalid_grant면 expired 표시, 일정은 유지, 이후 구글을 부르지 않는다", async () => {
  google({ events: { status: 200, items: EV } });
  const env = mkEnv();
  await call(env, "POST", "/phone/google", { code: "a" });
  const calls = google({ token: { status: 400, body: { error: "invalid_grant" } } });
  await stale(env);
  await call(env, "GET", "/car/schedule");
  const st = (await (await call(env, "GET", "/phone/google")).json()) as any;
  assert.deepEqual([st.connected, st.expired], [true, true]);
  assert.equal((await kv(env, "schedule")).length, 2);
  const n = calls.length;
  await stale(env);
  await call(env, "GET", "/car/schedule");
  assert.equal(calls.length, n);
});

test("/car/schedule: 마지막 시도 5분 안이면 구글을 안 부르고, 5분 넘으면 부른다", async () => {
  const calls = google({ events: { status: 200, items: EV } });
  const env = mkEnv();
  await call(env, "POST", "/phone/google", { code: "a" });
  const n = calls.length;
  await call(env, "GET", "/car/schedule");
  assert.equal(calls.length, n);
  await stale(env, Date.now() - 6 * 60_000);
  await call(env, "GET", "/car/schedule");
  assert.ok(calls.length > n);
});

test("연결이 없으면 구글 호출 0, 일정 그대로(scheduled 포함)", async () => {
  const calls = google();
  const env = mkEnv();
  await env.DB.prepare("INSERT INTO kv(key,value,updated_at) VALUES('schedule',?,0)").bind(JSON.stringify([{ x: 1 }])).run();
  assert.deepEqual(((await (await call(env, "GET", "/car/schedule")).json()) as any).items, [{ x: 1 }]);
  await worker.scheduled({}, env);
  assert.equal(calls.length, 0);
});

test("연결 끊기: revoke가 실패해도 google 삭제, 일정은 데모로", async () => {
  google({ events: { status: 200, items: EV } });
  const env = mkEnv();
  await call(env, "POST", "/phone/google", { code: "a" });
  globalThis.fetch = (async () => { throw new Error("x"); }) as unknown as typeof fetch;
  assert.equal((await call(env, "DELETE", "/phone/google")).status, 200);
  assert.equal(await kv(env, "google"), null);
  const s = await kv(env, "schedule");
  assert.equal(s.length, 3);
  assert.equal(s[0].title, "한빛물산 납품 미팅");
});
