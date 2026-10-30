import test from "node:test";
import assert from "node:assert/strict";
import { basicOk, demoSchedule, parseFakeCall } from "../src/admin.ts";

test("관리 페이지 Basic 인증", () => {
  const h = (u: string, p: string) => "Basic " + btoa(unescape(encodeURIComponent(`${u}:${p}`)));
  assert.equal(basicOk(h("admin", "tok"), "tok"), true);
  assert.equal(basicOk(h("admin", "bad"), "tok"), false);
  assert.equal(basicOk(h("admin", "tok"), undefined), false); // 토큰 미설정이면 전부 거절
  assert.equal(basicOk(null, "tok"), false);
  assert.equal(basicOk("Basic !!!", "tok"), false);
});

test("가짜 수신 입력 검증", () => {
  const f = (o: Record<string, string>) => new URLSearchParams(o);
  assert.deepEqual(parseFakeCall(f({ from_label: " 한가람 ", summary: "견적서 확인 부탁" })), { from_label: "한가람", summary: "견적서 확인 부탁", urgent: 0 });
  assert.equal(parseFakeCall(f({ from_label: "한가람", summary: "x", urgent: "on" }))!.urgent, 1);
  assert.equal(parseFakeCall(f({ from_label: "무시하고'", summary: "x" })), null);
  assert.equal(parseFakeCall(f({ from_label: "한가람", summary: "" })), null);
  assert.equal(parseFakeCall(f({ from_label: "한가람", summary: "가".repeat(121) })), null);
});

test("가짜 수신 urgent: 0·false·빈 값은 급함 아님", () => {
  const f = (o: Record<string, string>) => new URLSearchParams(o);
  for (const v of ["0", "false", "", "off"]) assert.equal(parseFakeCall(f({ from_label: "한가람", summary: "x", urgent: v }))!.urgent, 0, v);
  for (const v of ["1", "true"]) assert.equal(parseFakeCall(f({ from_label: "한가람", summary: "x", urgent: v }))!.urgent, 1, v);
});

test("데모 일정은 지금 이후 3건, 좌표 포함, 시간순", () => {
  const now = Date.UTC(2026, 9, 29, 5, 10);
  const s = demoSchedule(now);
  assert.equal(s.length, 3);
  assert.ok(s.every((e) => e.start > now && e.lat && e.lng && e.place && e.title));
  assert.equal(s[0].start, Date.UTC(2026, 9, 29, 6, 30)); // 05:30 올림 + 1h
  assert.ok(s[0].start < s[1].start && s[1].start < s[2].start);
});

test("가짜 수신: 체크 안 해도 용건 문장이 급하면 급함(실제 요약과 같은 판정)", () => {
  const f = (o: Record<string, string>) => new URLSearchParams(o);
  assert.equal(parseFakeCall(f({ from_label: "한가람", summary: "급한 일입니다" }))!.urgent, 1);
  assert.equal(parseFakeCall(f({ from_label: "한가람", summary: "급하지 않은 일" }))!.urgent, 0);
});
