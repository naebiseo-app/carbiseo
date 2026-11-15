// 이 테스트가 지키는 동작:
// - 문자 중계 호출(relaySend): URL·x-relay-secret·{to, body} 전달과 실패 코드 구분.
// - 데모 번호(010-0000-000x)는 실제로 보내지 않고 보낸 것으로만 기록한다.
import test from "node:test";
import assert from "node:assert/strict";
import { relaySend } from "../src/index.ts";

const env = { SMS_RELAY_URL: "https://relay.test/api/send", SMS_RELAY_SECRET: "s3cret" };
const mock = (status: number, body: unknown, seen: unknown[] = []) =>
  (async (url: string, init: RequestInit) => { seen.push({ url, init }); return new Response(JSON.stringify(body), { status }); }) as unknown as typeof fetch;

test("문자 중계: URL·x-relay-secret·{to, body}로 보내고 접수면 ok", async () => {
  const seen: any[] = [];
  const r = await relaySend(env, "01012345678", "안녕", mock(200, { messageId: "m1", status: "queued" }, seen));
  assert.deepEqual(r, { ok: true, http: 200, code: "queued", message_id: "m1" });
  assert.equal(seen[0].url, env.SMS_RELAY_URL);
  assert.equal(seen[0].init.headers["x-relay-secret"], "s3cret");
  assert.deepEqual(JSON.parse(seen[0].init.body), { to: "01012345678", body: "안녕" });
});

test("문자 중계 실패: 비밀 틀림 401·ClawOps 오류 코드·중계 다운", async () => {
  assert.deepEqual(await relaySend(env, "01012345678", "x", mock(401, { error: "unauthorized" })), { ok: false, http: 401, code: "unauthorized", message_id: null });
  assert.equal((await relaySend(env, "01012345678", "x", mock(400, { code: "invalid_to" }))).code, "invalid_to");
  assert.equal((await relaySend(env, "01012345678", "x", mock(500, "oops"))).code, "http_500");
  const down = (async () => { throw new Error("net"); }) as unknown as typeof fetch;
  assert.deepEqual(await relaySend(env, "01012345678", "x", down), { ok: false, http: 0, code: "relay_unreachable", message_id: null });
});

test("문자 중계: 데모 번호(010-0000-000x)는 보내지 않고 demo로 ok", async () => {
  const seen: any[] = [];
  assert.deepEqual(await relaySend(env, "010-0000-0001", "x", mock(200, {}, seen)), { ok: true, http: 200, code: "demo", message_id: null });
  assert.equal(seen.length, 0);
});
