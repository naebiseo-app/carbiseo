import test from "node:test";
import assert from "node:assert/strict";
import { summaryCard, transcriptTarget, statusDone } from "../src/index.ts";

// 10-30 실제 summary.completed 본문 형식(번호·ID는 가짜)
const body = (dir: string, sum: object) => new URLSearchParams({
  CallId: "CA1", AccountId: "AC1", From: "01012345678", To: "07000000000", Direction: dir,
  Event: "summary.completed", SummaryJson: JSON.stringify(sum),
}).toString();

test("받은 전화 요약 → 카드, 번호는 끝 4자리", () => {
  const c = summaryCard(body("inbound", { coreSummary: "김 과장, 견적 회신 요청", decisions: [], followUps: ["내일 오전 회신"] }));
  assert.deepEqual(c, { call_id: "CA1", from_label: "끝번호 5678", from_number: "01012345678", summary: "김 과장, 견적 회신 요청", urgent: 0 });
  assert.equal(summaryCard(body("inbound", { coreSummary: "x" }).replace("From=01012345678", "From=anonymous"))?.from_number, null); // 번호 없음
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

test("급함 판정: 실제 요약 문장 표현들", () => {
  const u = (s: string) => summaryCard(body("inbound", { coreSummary: s }))?.urgent;
  for (const s of ["거래처 김 과장, 납품 일정 관련 급한 건으로 회신 요청", "급합니다, 오늘 안으로 견적서 보내 달라고 함", "급해요 바로 전화 부탁",
    "빨리 연락 달라고 요청", "당장 확인이 필요하다고 함", "급한 일입니다", "시급합니다, 현장 확인 요청", "급히 통화 원함", "긴급 장애 접수"])
    assert.equal(u(s), 1, s);
  for (const s of ["급하지 않으니 내일 연락 달라", "안 급하니 천천히 보셔도 된다고 함", "급한 건은 아니고 견적 문의", "세금계산서 발급해 달라는 요청",
    "월급 지급 일정 문의", "부품 공급한 업체에서 확인 전화", "지난 회의 때 언급한 자료 요청", "급할 건 없고 시간 될 때 회신"])
    assert.equal(u(s), 0, s);
  assert.equal(summaryCard(body("inbound", { coreSummary: "견적 문의", followUps: ["오늘 중 빨리 회신"] }))?.urgent, 1); // 후속 조치도 본다
});

test("상태 통지: 끝난 통화만 받아쓰기 대상(폼·JSON)", () => {
  assert.equal(statusDone("CallStatus=completed&CallId=CAe210942f21a47ab8e7eb36072fff5480"), "CAe210942f21a47ab8e7eb36072fff5480");
  assert.equal(statusDone(JSON.stringify({ status: "completed", callId: "CAe210942f21a47ab8e7eb36072fff5480" })), "CAe210942f21a47ab8e7eb36072fff5480");
  assert.equal(statusDone("CallStatus=answered&CallId=CAe210942f21a47ab8e7eb36072fff5480"), null);
  assert.equal(statusDone("CallStatus=completed&CallId=../x"), null);
});

test("요약 참고 이름: 연락처·일정 제목·장소만, 중복·빈 값 제외", async () => {
  const { namesFrom } = await import("../src/index.ts");
  assert.deepEqual(namesFrom({ 이지은: "x", "우리딸 ❤": "y" }, [{ title: "한빛물산 미팅", place: "한빛물산" }, { title: "한빛물산 미팅" }, { title: " " }]), ["이지은", "우리딸 ❤", "한빛물산 미팅", "한빛물산"]);
  assert.deepEqual(namesFrom(null, null), []);
});

test("상호 자모 교정: 11-10 실측 오인식만 고치고 다른 말은 그대로", async () => {
  const { fixNames } = await import("../src/index.ts");
  const names = ["한빛물산 납품 미팅", "새봄상사 견적 상담", "김민수 과장", "판교역"];
  assert.equal(fixNames("황빛물산에서 급한 건으로 연락 주셨어요.", names), "한빛물산에서 급한 건으로 연락 주셨어요.");
  assert.equal(fixNames("세금 상사에서 전화하셨어요.", names), "새봄상사에서 전화하셨어요.");
  assert.equal(fixNames("새봄 상사에서 전화하셨어요.", names), "새봄상사에서 전화하셨어요.");
  assert.equal(fixNames("대한상사 박도윤 님이 회의 시간을 물으셨어요.", names), "대한상사 박도윤 님이 회의 시간을 물으셨어요.");
  assert.equal(fixNames("오늘 중으로 다시 받아 보고 싶다고 하셨어요.", names), "오늘 중으로 다시 받아 보고 싶다고 하셨어요.");
});
