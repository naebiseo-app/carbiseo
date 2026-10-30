import test from "node:test";
import assert from "node:assert/strict";
import { summaryCard, transcriptTarget } from "../src/index.ts";

// 10-30 실제 summary.completed 본문 형식(번호·ID는 가짜)
const body = (dir: string, sum: object) => new URLSearchParams({
  CallId: "CA1", AccountId: "AC1", From: "01012345678", To: "07000000000", Direction: dir,
  Event: "summary.completed", SummaryJson: JSON.stringify(sum),
}).toString();

test("받은 전화 요약 → 카드, 번호는 끝 4자리", () => {
  const c = summaryCard(body("inbound", { coreSummary: "김 과장, 견적 회신 요청", decisions: [], followUps: ["내일 오전 회신"] }));
  assert.deepEqual(c, { call_id: "CA1", from_label: "끝번호 5678", summary: "김 과장, 견적 회신 요청", urgent: 0 });
});

test("급함 낱말 판정, 부정은 제외", () => {
  assert.equal(summaryCard(body("inbound", { coreSummary: "급하게 연락 원함" }))?.urgent, 1);
  assert.equal(summaryCard(body("inbound", { coreSummary: "급하지 않은 일" }))?.urgent, 0);
});

test("건 전화·다른 이벤트·깨진 JSON", () => {
  assert.equal(summaryCard(body("outbound", { coreSummary: "x" })), null);
  assert.equal(summaryCard("Event=transcript.completed&Direction=inbound"), null);
  assert.equal(summaryCard("Event=summary.completed&Direction=inbound&SummaryJson=%7B")?.summary, "");
});

test("AI 통화 종료 → 받아쓰기 요청 대상", () => {
  assert.equal(transcriptTarget("Event=agent.disconnected&CallId=CA9&Direction=inbound"), "CA9");
  assert.equal(transcriptTarget("Event=agent.disconnected&CallId=CA9"), "CA9"); // 방향 필드가 없어도 요청(409면 무해)
  assert.equal(transcriptTarget("Event=agent.disconnected&CallId=CA9&Direction=outbound"), null);
  assert.equal(transcriptTarget("Event=agent.connected&CallId=CA9"), null);
});
