// 이 테스트가 지키는 동작:
// - 말동무 /car/chat: 키 없음·API 실패·금칙어는 고정 문구, 졸음 말은 AI 없이 휴식 권유로 답한다.
// - 입력 text 200자·history 마지막 6개, 답은 문장 단위로 110자 안까지만 남긴다.
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
  assert.equal(sent[0].body.max_tokens, 200);
  assert.deepEqual(sent[0].body.thinking, { type: "disabled" });
  assert.equal(sent[0].headers["x-api-key"], "k");
  assert.equal(sent[0].headers["anthropic-version"], "2023-06-01");
});
test("졸음 말은 키가 없어도 AI 없이 고정 휴식 권유", async () => {
  globalThis.fetch = (async () => { throw new Error("called"); }) as unknown as typeof fetch;
  for (const text of ["나 졸려?", "너 졸려?", "너무 피곤해", "잠 와"]) {
    const j: any = await (await call(env(), { text })).json();
    assert.equal(j.source, "fallback"); assert.match(j.reply, /쉬어 가세요/);
  }
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
test("긴 답은 문장 단위로 110자 안까지만 남긴다", async () => {
  const a = "가".repeat(60) + "요. ", b = "나".repeat(60) + "요?";
  mock(a + b);
  const j: any = await (await call(env("k"), { text: "안녕" })).json();
  assert.equal(j.reply, a.trim()); assert.equal(j.source, "llm");
});
test("잘린 답(max_tokens)은 완성된 문장까지 읽는다", async () => {
  globalThis.fetch = (async () => ({ ok: true, json: async () => ({ stop_reason: "max_tokens", content: [{ type: "text", text: "옛날에 토끼가 살았어요. 어느 날 토" }] }) })) as unknown as typeof fetch;
  assert.deepEqual(await (await call(env("k"), { text: "안녕" })).json(), { reply: "옛날에 토끼가 살았어요.", source: "llm" });
});
test("완성 문장 없이 잘린 답은 fallback", async () => {
  globalThis.fetch = (async () => ({ ok: true, json: async () => ({ stop_reason: "max_tokens", content: [{ type: "thinking" }, { type: "text", text: "운전 중에 많" }] }) })) as unknown as typeof fetch;
  assert.deepEqual(await (await call(env("k"), { text: "안녕" })).json(), { reply: FALLBACK.chat, source: "fallback" });
});

test("정차 중 말동무는 운전 중 전제를 빼고 그 말을 금한다", async () => {
  const { chatSystemParked } = await import("../src/index.ts");
  const s = chatSystemParked();
  assert.ok(!s.includes("운전 중이므로"));
  assert.ok(s.includes("운전 중이라는 말은 하지 말고"));
});
