import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.ts";
import { ownerScript } from "../src/script.ts";
import { mailsHtml } from "../src/admin.ts";

// 최소 D1 흉내: kv와 inbox INSERT 인자를 기억한다.
function fakeDB() {
  const kv: Record<string, string> = {};
  const inbox: unknown[][] = [];
  return {
    kv, inbox,
    prepare(sql: string) {
      let args: unknown[] = [];
      const st = {
        bind(...a: unknown[]) { args = a; return st; },
        async run() {
          const k = sql.match(/VALUES\('(\w+)',\?/)?.[1];
          if (k) kv[k] = args[0] as string;
          if (sql.startsWith("INSERT INTO inbox")) inbox.push(args);
          return { meta: { changes: 1, last_row_id: inbox.length } };
        },
        async first() { const v = sql.includes("key=?") ? kv[args[0] as string] : undefined; return v == null ? null : { value: v }; },
        async all() { return { results: [] }; },
      };
      return st;
    },
  };
}
const mkEnv = () => ({ DB: fakeDB(), CAR_TOKEN: "t", CALL_CONTEXT_SECRET: "cc", SMS_RELAY_URL: "https://relay.test/send", SMS_RELAY_SECRET: "s" }) as any;
const post = (env: any, path: string, body: unknown, auth = true) =>
  worker.fetch(new Request("https://x" + path, { method: "POST", headers: auth ? { authorization: "Bearer t" } : {}, body: JSON.stringify(body) }), env);

test("/car/mail: 정상 저장·제목 기본값·confirmed 없음 400·인증 없음 401", async () => {
  const env = mkEnv();
  const r = await post(env, "/car/mail", { to: "김부장", subject: "견적", body: "견적서 보냅니다", confirmed: true });
  assert.deepEqual(await r.json(), { ok: true, id: 1 });
  assert.deepEqual(env.DB.inbox[0].slice(0, 2), ["김부장", "견적서 보냅니다"]);
  assert.deepEqual(JSON.parse(env.DB.inbox[0][3] as string), { channel: "mail", subject: "견적" });
  await post(env, "/car/mail", { to: "a@b.c", body: "가".repeat(30), confirmed: true });
  assert.equal(JSON.parse(env.DB.inbox[1][3] as string).subject, "가".repeat(20));
  assert.equal((await post(env, "/car/mail", { to: "a", body: "b" })).status, 400);
  assert.equal((await post(env, "/car/mail", { to: "", body: "b", confirmed: true })).status, 400);
  assert.equal((await post(env, "/car/mail", { to: "a", body: "b", confirmed: true }, false)).status, 401);
});

test("settings family_number: 저장·마스킹·삭제·잘못된 값 400", async () => {
  const env = mkEnv();
  const r = (await (await post(env, "/car/settings", { family_number: "010-1111-2222" })).json()) as any;
  assert.equal(r.settings.family_number, "****2222");
  assert.equal(r.settings.family_label, "****2222");
  assert.equal(JSON.parse(env.DB.kv.settings).family_number, "01011112222");
  assert.equal((await post(env, "/car/settings", { family_number: "12345" })).status, 400);
  assert.equal((await post(env, "/car/settings", { family_number: 1011112222 })).status, 400);
  const d = (await (await post(env, "/car/settings", { family_number: "" })).json()) as any;
  assert.equal(d.settings.family_number, undefined);
  assert.equal(JSON.parse(env.DB.kv.settings).family_number, undefined);
});

test("/car/family-eta: 번호 없음 skipped·하루 한 번·정상 발송", async () => {
  const env = mkEnv();
  assert.equal((await post(env, "/car/family-eta", { eta_min: 200 })).status, 400);
  assert.deepEqual(await (await post(env, "/car/family-eta", { eta_min: 5 })).json(), { ok: true, skipped: "no_family" });
  await post(env, "/car/settings", { family_number: "01011112222", owner_label: "김도윤" });
  const seen: any[] = [];
  const real = globalThis.fetch;
  globalThis.fetch = (async (u: string, init: RequestInit) => { seen.push({ u, init }); return new Response(JSON.stringify({ messageId: "m1", status: "queued" })); }) as any;
  try {
    const r = (await (await post(env, "/car/family-eta", { eta_min: 10 })).json()) as any;
    assert.deepEqual(r, { ok: true, http: 200, code: "queued", message_id: "m1" });
    const sent = JSON.parse(seen[0].init.body);
    assert.equal(sent.to, "01011112222");
    assert.match(sent.body, /^김도윤님 \d\d:\d\d쯤 도착 예정이에요\. \(내비서\)$/);
    assert.equal(JSON.parse(env.DB.inbox[0][4] as string).channel, "family");
    assert.deepEqual(await (await post(env, "/car/family-eta", { eta_min: 10 })).json(), { ok: true, skipped: "sent_today" });
    assert.equal(seen.length, 1);
  } finally { globalThis.fetch = real; }
});

test("ownerScript에 '메일:' 안내, 관리 페이지 메일 목록", () => {
  assert.match(ownerScript({ room: "PARK", eta: null, schedule: null, recent: [], nowMs: 0 }), /메일이면 받는 사람.*모두 되묻고.*할 일로 남겨 줘/);
  assert.match(mailsHtml([{ from_label: "김부장", summary: "가".repeat(100), created_at: 0, meta: '{"subject":"견적"}' }]), /김부장님께 · 견적 · 가{80} · 09:00/);
});
