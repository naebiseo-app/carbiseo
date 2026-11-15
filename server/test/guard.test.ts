// 이 테스트가 지키는 동작:
// - AI가 만든 문장은 120자·금칙어·전화번호 형태 숫자열 검사를 거치고, 실패하면 고정 문구로 바뀐다.
// - 사용자가 확인한 본문(문자·답장)은 자르지 않고 금칙어만 막는다.
import { test } from "node:test";
import assert from "node:assert/strict";
import { guardText, isBlocked, FALLBACK } from "../src/guard.ts";
import { parseSend, taskText } from "../src/index.ts";
import { parseReply } from "../src/notif.ts";

test("정상 문장은 그대로", () => {
  assert.equal(guardText("summary", "김 과장, 견적 회신 요청. 내일 오전 회신."), "김 과장, 견적 회신 요청. 내일 오전 회신.");
  assert.equal(guardText("summary", "2026-11-01 14:30 회의 변경"), "2026-11-01 14:30 회의 변경"); // 날짜·시각은 번호 아님
});
test("120자 초과는 문장 단위로 자르고 마침표로 끝(… 없음)", () => {
  const a = "가".repeat(60) + ".", b = "나".repeat(60) + ".", c = "다".repeat(60) + ".";
  assert.equal(guardText("summary", `${a} ${b} ${c}`), a); // a+b = 123자 > 120
  const g = guardText("draft", "라".repeat(300));
  assert.equal(g.length, 120); assert.ok(g.endsWith(".") && !g.includes("…"));
});
test("금칙어는 고정 문구로 대체", () => {
  assert.equal(guardText("summary", "이 병신아 전화해"), FALLBACK.summary);
  assert.equal(guardText("draft", "씨 발 뭐해"), FALLBACK.draft); // 공백으로 우회해도
});
test("전화번호·주민번호 형태 숫자열은 대체, 8자리 이하는 통과", () => {
  assert.equal(guardText("summary", "010-0000-0000으로 전화 주세요"), FALLBACK.summary);
  assert.equal(guardText("summary", "01000000000 으로"), FALLBACK.summary);
  assert.equal(guardText("summary", "000000-1000000"), FALLBACK.summary);
  assert.equal(guardText("summary", "3시 30분, 12345678번"), "3시 30분, 12345678번");
});
test("빈 문장도 고정 문구", () => {
  assert.equal(guardText("summary", ""), FALLBACK.summary);
  assert.equal(guardText("summary", null), FALLBACK.summary);
});
test("보내는 문자·답장은 금칙어만 막고 길어도 자르지 않는다", () => {
  const long = "가".repeat(500);
  assert.equal((parseSend({ confirmed: true, to_number: "01000000000", body: long }) as { body: string }).body, long);
  assert.deepEqual(parseSend({ confirmed: true, to_number: "01000000000", body: "병신" }), { error: "blocked body" });
  assert.deepEqual(parseReply({ confirmed: true, notif_id: 1, text: "닥쳐" }), { error: "blocked text" });
  assert.ok(isBlocked("연락처 010-0000-0000") && !isBlocked("도착했어요"));
});
test("할 일 본문도 가드(taskText 자체는 그대로)", () => {
  assert.equal(guardText("summary", taskText("SummaryJson=%7B")), FALLBACK.summary);
});

test("사용자가 확인한 본문(numbers=false)은 회신 번호를 막지 않고 금칙어만 막는다", () => {
  assert.equal(isBlocked("010-0000-0000으로 연락 주세요.", false), false);
  assert.equal(isBlocked("씨발", false), true);
});
