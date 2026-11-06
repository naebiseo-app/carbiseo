import test from "node:test";
import assert from "node:assert/strict";
import { callerNumber, hhmm, nextEventAt, ownerScript, parseMode, pickScript, sameNumber } from "../src/script.ts";
import { parseSend, taskText, taskTexts } from "../src/index.ts";

test("발신번호: 폼·JSON, 키 대소문자 무시, 숫자만 비교", () => {
  assert.equal(callerNumber("from=010-1111-2222&To=070"), "010-1111-2222");
  assert.equal(callerNumber('{"FROM":"+821011112222"}'), "+821011112222");
  assert.equal(callerNumber("To=070"), "");
  assert.ok(sameNumber("010-1111-2222", "01011112222"));
  assert.ok(sameNumber("+821011112222", "01011112222"));
  assert.ok(!sameNumber("01099998888", "01011112222"));
  assert.ok(!sameNumber("", ""));
  assert.ok(!sameNumber("01011112222", undefined));
});

test("주인 대본: 일정·최근 전화·문자 안내, 4000자 이하", () => {
  const nowMs = Date.UTC(2026, 9, 30, 1, 0); // 10:00 KST
  const s = ownerScript({
    owner: "김민수", room: "DRIVE_WORK", eta: 12, nowMs, recent: [{ from_label: "끝번호 5678", summary: "견적 회신 요청", urgent: 1 }],
    schedule: [{ title: "지난 회의", start: nowMs - 1000 }, { title: "납품 미팅", start: nowMs + 3_600_000, place: "판교역" }],
  });
  assert.match(s, /김민수님 본인/);
  assert.match(s, /짧게 답합니다/);
  assert.match(s, /11:00 납품 미팅\(판교역\)/);
  assert.doesNotMatch(s, /지난 회의/);
  assert.match(s, /끝번호 5678: 견적 회신 요청 \[급함\]/);
  assert.match(s, /보내기 버튼을 눌러 주세요/);
  assert.match(s, /약 12분/);
  assert.doesNotMatch(ownerScript({ room: "PARK", eta: null, schedule: null, recent: [], nowMs }), /짧게 답합니다/);
  const long = ownerScript({ room: null, eta: null, schedule: null, nowMs, recent: Array(5).fill({ from_label: "x", summary: "가".repeat(2000), urgent: 0 }) });
  assert.equal(long.length, 4000);
});

test("응대 모드: ment·off는 짧은 안내만, ai는 기존 대본", () => {
  assert.equal(parseMode("ment"), "ment");
  assert.equal(parseMode("x"), null);
  assert.match(pickScript("PARK", "주인", undefined, null, "ment").instruction, /^전화가 연결되면 인사 없이 첫 마디로 '주인님이 지금 운전 중이라 전화를 받을 수 없습니다. 정차하시면 연락드리겠습니다. 이 통화는 녹음됩니다.'라고만 말하고, 말을 마치면 상대 대답을 기다리지 않고 즉시 통화를 종료합니다/);
  assert.match(pickScript("DRIVE_WORK", "김도윤", undefined, 25, "ment").instruction, /'김도윤님이 지금 운전 중이라 전화를 받을 수 없습니다. 약 25분 뒤 도착하시면 연락드리겠습니다.'라고만/);
  // 끔 = 기존 저장값 호환, 멘트와 같은 대본(사용자 멘트는 안 씀)
  assert.equal(pickScript("DRIVE_WORK", "주인", undefined, 7, "off", null, null, Date.now(), "회의 중").instruction, pickScript("DRIVE_WORK", "주인", undefined, 7, "ment").instruction);
  assert.doesNotMatch(pickScript("DRIVE_WORK", undefined, undefined, null, "off").instruction, /지금은 받을 수 없습니다|{owner}|님이/);
  assert.match(pickScript("DRIVE_WORK", "주인", undefined, 9, "ment", null, null, Date.now(), "회의 중이에요.").instruction, /'회의 중이에요. 약 9분 뒤 도착하시면 연락드리겠습니다.'라고만/);
  assert.match(pickScript("DRIVE_WORK", "주인", undefined, null, "ment", null, null, Date.now(), "회의 중이에요.").instruction, /'회의 중이에요.'라고만/);
  assert.equal(pickScript("PARK", "주인", undefined, null, "ai").instruction, pickScript("PARK", "주인").instruction);
});

test("다음 일정 한 줄: DRIVE_WORK에만, KST", () => {
  const nowMs = Date.UTC(2026, 9, 30, 5, 0);
  const at = nextEventAt([{ title: "b", start: nowMs + 7_200_000 }, { title: "a", start: nowMs + 1_800_000 }, { title: "old", start: nowMs - 1 }], nowMs);
  assert.equal(hhmm(at!), "14:30");
  assert.equal(nextEventAt([], nowMs), null);
  assert.equal(nextEventAt(null, nowMs), null);
  assert.match(pickScript("DRIVE_WORK", "주인", undefined, null, "ai", at).instruction, /다음 일정 14:30 이후 연락 가능\.$/);
  assert.doesNotMatch(pickScript("DRIVE_REST", "주인", undefined, null, "ai", at).instruction, /다음 일정/);
  assert.equal(pickScript("DRIVE_WORK", "주인", undefined, null, "ai", null).instruction, pickScript("DRIVE_WORK", "주인").instruction);
});

test("할 일 카드 본문 = 요약 + 후속 조치", () => {
  const raw = new URLSearchParams({ SummaryJson: JSON.stringify({ coreSummary: "박 대리에게 문자", followUps: ["내용: 3시 도착"] }) }).toString();
  assert.equal(taskText(raw), "박 대리에게 문자 / 내용: 3시 도착");
  assert.equal(taskText("SummaryJson=%7B"), "");
});

test("주인 통화 요청 2건 = 카드 2장, 메일 줄은 따로 보존", () => {
  const mk = (followUps: string[]) => new URLSearchParams({ SummaryJson: JSON.stringify({ coreSummary: "일정 정리", followUps }) }).toString();
  assert.deepEqual(taskTexts(mk(["박도윤 과장에게 문자: 3시 도착", "메일: 이지은 / 회의 안내 / 내일 10시"])), ["일정 정리 / 박도윤 과장에게 문자: 3시 도착", "메일: 이지은 / 회의 안내 / 내일 10시"]);
  assert.equal(taskTexts(mk(["메일: 이지은 / 제목 / 내용", "김민수에게 문자"])).length, 2);
  assert.deepEqual(taskTexts(mk([])), ["일정 정리"]);
});

test("문자 발송 검증: confirmed·번호 10~11자리·내용", () => {
  assert.deepEqual(parseSend({ to_number: "010-1234-5678", body: " 안녕 ", confirmed: true, to_label: "박 대리" }), { to: "01012345678", body: "안녕", label: "박 대리" });
  assert.equal(parseSend({ to_number: "01012345678", body: "x", confirmed: true }).label, "끝번호 5678");
  assert.ok("error" in parseSend({ to_number: "01012345678", body: "x" }));
  assert.ok("error" in parseSend({ to_number: "01012345678", body: "x", confirmed: "true" }));
  assert.ok("error" in parseSend({ to_number: "1234", body: "x", confirmed: true }));
  assert.ok("error" in parseSend({ to_number: "010123456789", body: "x", confirmed: true }));
  assert.ok("error" in parseSend({ to_number: "01012345678", body: "  ", confirmed: true }));
  assert.ok("error" in parseSend({ to_number: "01012345678", body: "가".repeat(1001), confirmed: true }));
});

test("주인 대화: 운전 2시간 이상이면 짧게·정차 후로 미루자고 먼저 제안", () => {
  const o = { room: "DRIVE_WORK" as const, eta: null, schedule: null, recent: [], nowMs: 0 };
  const MSG = /운전 2시간이 넘었습니다\. 업무 대화는 한두 문장으로 짧게 하고, 긴 일은 정차 후로 미루자고 먼저 제안합니다/;
  assert.doesNotMatch(ownerScript({ ...o, trip_min: 119 }), MSG);
  assert.doesNotMatch(ownerScript(o), MSG);
  assert.match(ownerScript({ ...o, trip_min: 120 }), MSG);
});
