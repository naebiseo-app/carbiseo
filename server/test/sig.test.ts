// 이 테스트가 지키는 동작:
// - ClawOps 웹훅 서명 대상 문자열 = URL + 파라미터를 키 사전순으로 key+value 이어붙인 것(폼·JSON 모두).
import test from "node:test";
import assert from "node:assert/strict";
import { signData } from "../src/index.ts";

test("서명 대상 = URL + 키 사전순 key+value (폼)", async () => {
  const url = "https://x.dev/clawops/events";
  const data = signData(url, "application/x-www-form-urlencoded", "To=070&CallId=c1&Body=a+b");
  assert.equal(data, url + "Bodya bCallIdc1To070");
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode("whsec_test"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(data));
  assert.equal(btoa(String.fromCharCode(...new Uint8Array(mac))), "ndssng6wokiJDJfJYm9dVdcQschHuRouDWiV10hJMlI="); // 파이썬 문서 예제로 계산한 값
});

test("JSON 본문도 최상위 키 사전순", () => {
  assert.equal(signData("u", "application/json", '{"b":"2","a":1}'), "ua1b2");
});
