import test from "node:test";
import assert from "node:assert/strict";
import { demoSchedule, encodeReplay, freeSlots, MEET_AT, replayScript, slotLabel, travelMin, type DemoEvent } from "../src/admin.ts";

const H = 3_600_000, MIN = 60_000;
const kst = (d: number, h: number, m = 0) => Date.UTC(2026, 10, d, h - 9, m); // 2026-11-d h:m KST. 11-02 = 월요일
const ev = (start: number, o: Partial<DemoEvent> = {}): DemoEvent => ({ title: "x", start, place: "판교역", ...MEET_AT, contact: "", contact_number: "", ...o });

test("이동시간 = 직선거리 ÷ 30km/h", () => {
  const pangyo = { lat: 37.3948, lng: 127.1112 }, suwon = { lat: 37.2636, lng: 127.0286 };
  assert.equal(travelMin(pangyo, pangyo), 0);
  assert.equal(travelMin(pangyo, suwon), 33); // 약 16.3km
});

test("빈 시간: 평일 업무시간 안에서 30분 단위, 둘째는 첫째보다 3시간 뒤", () => {
  const now = kst(2, 8); // 월요일 08:00, 일정 없음
  assert.deepEqual(freeSlots([], now, null), [kst(2, 9), kst(2, 12)]);
});

test("빈 시간: 일정과 겹치면 건너뜀(미팅 장소와 같은 곳이라 이동 0분)", () => {
  const now = kst(2, 8);
  assert.deepEqual(freeSlots([ev(kst(2, 9)), ev(kst(2, 12))], now, null), [kst(2, 10), kst(2, 13)]); // 9~10·12~13시 일정 → 10시, 13시
});

test("빈 시간: 일정 앞뒤로 이동시간을 뺀다", () => {
  const now = kst(2, 8);
  const far = ev(kst(2, 12), { lat: 37.5663, lng: 126.9779 }); // 서울시청: 판교까지 이동 ≥ 30분
  const move = travelMin(far, MEET_AT);
  assert.ok(move >= 30);
  const [a, b] = freeSlots([far], now, null);
  assert.equal(a, kst(2, 9)); // 9~10시 + 이동 뒤에도 12시 일정 전에 끝남
  assert.ok(b >= kst(2, 13) + move * MIN - 30 * MIN); // 일정 끝(13시) + 이동시간 뒤
  const s = freeSlots([far], kst(2, 8), null, 99);
  for (const t of s) assert.ok(t + H <= far.start - move * MIN || t >= far.start + H + move * MIN, `겹침 ${t}`);
});

test("빈 시간: 도착 전(지금+eta_min)은 제외", () => {
  const now = kst(2, 9, 10);
  assert.equal(freeSlots([], now, null)[0], kst(2, 9, 30));
  assert.equal(freeSlots([], now, 55)[0], kst(2, 10, 30)); // 10:05 도착 → 10:30 시작(30분 단위 올림)
  assert.equal(freeSlots([], now, 50)[0], kst(2, 10)); // 10:00 도착 → 바로 10:00
});

test("빈 시간: 업무시간 밖(저녁·주말)은 제외하고 다음 평일로", () => {
  const sat = Date.UTC(2026, 10, 7, 3); // 토요일 12:00 KST
  assert.deepEqual(freeSlots([], sat, null), [kst(9, 9), kst(9, 12)]); // 월요일(11-09)
  const fri = kst(6, 17, 30); // 금요일 17:30: 오늘은 18시 전에 1시간 못 채움 → 월요일
  assert.deepEqual(freeSlots([], fri, null), [kst(9, 9), kst(9, 12)]);
  for (const t of freeSlots([], kst(2, 8), null, 50)) {
    const h = new Date(t + 9 * H).getUTCHours();
    assert.ok(h >= 9 && h < 18);
  }
});

test("빈 시간: 하루가 꽉 차면 다음 날 / 모자라면 있는 만큼", () => {
  const now = kst(2, 8);
  const full = [9, 10, 11, 12, 13, 14, 15, 16, 17].map((h) => ev(kst(2, h)));
  assert.deepEqual(freeSlots(full, now, null), [kst(3, 9), kst(3, 12)]);
  assert.equal(freeSlots([], now, null, 1).length, 1);
});

test("시각 표현: 오늘·내일·모레·요일, 오전/오후", () => {
  const now = kst(2, 8);
  assert.equal(slotLabel(kst(2, 15, 30), now), "오늘 오후 3시 30분");
  assert.equal(slotLabel(kst(3, 9), now), "내일 오전 9시");
  assert.equal(slotLabel(kst(4, 12), now), "모레 오후 12시");
  assert.equal(slotLabel(kst(6, 10), now), "금요일 오전 10시");
});

test("대본 순서: 고지가 맨 앞, 시각 오름차순, 정정 뒤 운전자 대사", () => {
  const now = kst(2, 8);
  const r = replayScript(demoSchedule(now), now, 20);
  assert.equal(r.lines[0].who, "ai");
  assert.equal(r.lines[0].text, "AI 비서가 함께 듣습니다");
  assert.equal(r.lines[0].t, 0);
  assert.deepEqual(r.lines.map((l) => l.t), [...r.lines.map((l) => l.t)].sort((a, b) => a - b));
  assert.ok(r.lines.at(-1)!.t < r.endMs);
  assert.deepEqual(r.lines.filter((l) => l.who === "hint").map((l) => l.label), ["추천 답변", "사실 정정", "빈 시간"]);
  const i = r.lines.findIndex((l) => l.who === "driver" && /25만/.test(l.text));
  assert.equal(r.lines[i + 1].label, "사실 정정"); // 틀린 말 바로 뒤에 정정
  assert.match(r.lines[i + 1].text, /^실제로는 개당 23만 원이에요 \(한빛물산 견적 카드, 합계 1,150만 원\)$/);
});

test("대본: 일정·ETA가 문장에 반영되고 도움 카드는 120자 이하", () => {
  const now = kst(2, 8);
  const r = replayScript(demoSchedule(now), now, 20);
  const hints = r.lines.filter((l) => l.who === "hint");
  assert.match(hints[0].text, /오늘 .+ 판교역 납품 미팅/); // 한빛물산 일정(김민수 과장)
  assert.match(hints[2].text, /^비는 시간은 .+, 아니면 .+예요 \(일정·이동 시간 반영\)$/);
  assert.ok(hints.every((h) => h.text.length <= 120) && r.wrap.length <= 120);
  assert.match(r.wrap, /약속: .*단가 개당 23만 원/);
  const late = replayScript(demoSchedule(now), now, 600); // 10시간 뒤 도착 → 오늘은 불가, 비는 시간이 달라진다
  assert.notEqual(late.lines.find((l) => l.label === "빈 시간")!.text, hints[2].text);
});

test("대본: 가드를 거친다(금칙어가 든 일정 이름은 도움 카드에서 빠짐)", () => {
  const now = kst(2, 8);
  const bad = [ev(kst(2, 10), { contact: "김민수 과장", place: "씨발역" })];
  const r = replayScript(bad, now, null);
  assert.equal(r.lines.some((l) => l.who === "hint" && /씨발/.test(l.text)), false);
  assert.equal(r.lines.filter((l) => l.who === "hint").length, 2); // 추천 답변 카드만 빠짐
});

test("차에 내려 주는 형식: 첫 줄 제목·끝, 다음 줄 t\twho\tlabel\ttext, 탭·줄바꿈 제거", () => {
  const out = encodeReplay({ title: "거래처", endMs: 9000, lines: [{ t: 0, who: "ai", label: "", text: "고지" }, { t: 500, who: "hint", label: "라벨", text: "가\n나\t다" }], wrap: "" }).split("\n");
  assert.deepEqual(out, ["거래처\t9000", "0\tai\t\t고지", "500\thint\t라벨\t가 나 다"]);
});
