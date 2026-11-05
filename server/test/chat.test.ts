import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.ts";
import { FALLBACK } from "../src/guard.ts";

const real = globalThis.fetch;
test.afterEach(() => { globalThis.fetch = real; });

const call = (env: any, body: unknown, auth = true) =>
  worker.fetch(new Request("https://x/car/chat", { method: "POST", headers: auth ? { authorization: "Bearer t" } : {}, body: JSON.stringify(body) }), env);
const env = (key?: string) => ({ CAR_TOKEN: "t", ANTHROPIC_API_KEY: key }) as any;
// 모킹: 요청 본문을 sent에 모으고 주어진 응답을 돌려준다
function mock(text: string, ok = true) {
  const sent: any[] = [];
  globalThis.fetch = (async (_u: string, init: any) => { sent.push({ url: _u, headers: init.headers, body: JSON.parse(init.body) }); return { ok, json: async () => ({ content: [{ type: "text", text }] }) }; }) as unknown as typeof fetch;
  return sent;
}

test("키 없음: no_key 고정 문구, API 호출 안 함", async () => {
  globalThis.fetch = (async () => { throw new Error("called"); }) as unknown as typeof fetch;
  const r = await call(env(), { text: "안녕" });
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { reply: FALLBACK.chat, source: "no_key" });
});
test("빈 text는 400", async () => {
  assert.equal((await call(env("k"), { text: "   " })).status, 400);
  assert.equal((await call(env("k"), {})).status, 400);
});
test("성공: llm, 모델·max_tokens·헤더 확인", async () => {
  const sent = mock("오늘 날씨가 좋네요. 어디 가시는 길이세요?");
  const r = await call(env("k"), { text: "안녕" });
  assert.deepEqual(await r.json(), { reply: "오늘 날씨가 좋네요. 어디 가시는 길이세요?", source: "llm" });
  assert.equal(sent[0].url, "https://api.anthropic.com/v1/messages");
  assert.equal(sent[0].body.model, "claude-haiku-5-5");
  assert.equal(sent[0].body.max_tokens, 120);
  assert.equal(sent[0].headers["x-api-key"], "k");
  assert.equal(sent[0].headers["anthropic-version"], "2023-06-01");
});
test("API 500이면 fallback", async () => {
  mock("x", false);
  assert.deepEqual(await (await call(env("k"), { text: "안녕" })).json(), { reply: FALLBACK.chat, source: "fallback" });
});
test("fetch 예외(타임아웃)도 fallback", async () => {
  globalThis.fetch = (async () => { throw new Error("timeout"); }) as unknown as typeof fetch;
  assert.equal((await (await call(env("k"), { text: "안녕" })).json() as any).source, "fallback");
});
test("text는 200자로 자른다", async () => {
  const sent = mock("네.");
  await call(env("k"), { text: "가".repeat(300) });
  assert.equal(sent[0].body.messages.at(-1).content.length, 200);
});
test("history는 마지막 6개만, 각 200자", async () => {
  const sent = mock("네.");
  const history = Array.from({ length: 10 }, (_, i) => ({ role: i % 2 ? "assistant" : "user", text: `${i}`.repeat(300) }));
  await call(env("k"), { text: "끝", history });
  const m = sent[0].body.messages;
  assert.equal(m.length, 7); // history 6 + 현재 text
  assert.equal(m[0].content[0], "4"); // 10개 중 마지막 6개 = 4..9
  assert.ok(m.every((x: any) => x.content.length <= 200));
});
test("금칙어 답은 가드가 고정 문구로 대체", async () => {
  mock("이 병신아");
  assert.deepEqual(await (await call(env("k"), { text: "안녕" })).json(), { reply: FALLBACK.chat, source: "fallback" });
});
test("미인증은 401", async () => {
  assert.equal((await call(env("k"), { text: "안녕" }, false)).status, 401);
});
