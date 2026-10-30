import test from "node:test";
import assert from "node:assert/strict";
import { demoSchedule, freeSlot, replayCards, travelMin } from "../src/admin.ts";

test("이동시간 = 직선거리 ÷ 30km/h", () => {
  const pangyo = { lat: 37.3948, lng: 127.1112 }, suwon = { lat: 37.2636, lng: 127.0286 };
  assert.equal(travelMin(pangyo, pangyo), 0);
  assert.equal(travelMin(pangyo, suwon), 33); // 약 16.3km
});

test("빈 시간: 이동시간 빼고 30분 이상", () => {
  const h = 3_600_000, t = Date.UTC(2026, 9, 30, 0, 0);
  const p = { place: "x", lat: 37.5, lng: 127 };
  const s = [{ ...p, title: "a", start: t }, { ...p, title: "b", start: t + 1.5 * h }, { ...p, title: "c", start: t + 4 * h, lat: 37.6 }];
  const f = freeSlot(s, t)!; // a~b 사이 30분은 이동 0분이라 통과
  assert.equal(f.from, t + 0.5 * h);
  assert.equal(f.to, t + 1.5 * h);
  assert.equal(freeSlot(s.slice(0, 1), t), null);
  assert.equal(freeSlot([{ ...p, title: "a", start: t }, { ...p, title: "b", start: t + 50 * 60_000 }], t), null);
});

test("재현 카드 3장, 8초 간격, 정정 견적은 데모 데이터", () => {
  const t = Date.UTC(2026, 9, 30, 0, 0);
  const c = replayCards(demoSchedule(t), t);
  assert.deepEqual(c.map((x) => x.created_at - t), [0, 8000, 16000]);
  assert.deepEqual(c.map((x) => x.step), [3, 4, 5]);
  assert.match(c[0].text, /4시 가능/);
  assert.match(c[1].text, /견적은 1,150만 원입니다/);
  assert.match(c[2].text, /^\d\d:\d\d~\d\d:\d\d 비어요\(이동 \d+분 포함\)$/);
});
