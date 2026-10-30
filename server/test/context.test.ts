import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { INBOX_SQL } from "../src/index.ts";
import { carChip, parseCarCtx, pickScript } from "../src/script.ts";

test("폴링: 시각 안 된 hint보다 뒤 행은 hint가 보일 때까지 기다린다(건너뛰기 방지)", () => {
  const db = new DatabaseSync(":memory:");
  db.exec("CREATE TABLE inbox (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, created_at INTEGER)");
  const add = (k: string, t: number) => db.prepare("INSERT INTO inbox(kind,created_at) VALUES(?,?)").run(k, t);
  add("call", 100); add("hint", 100); add("hint", 108); add("hint", 116); add("call", 105); // id 1..5
  const poll = (since: number, now: number) => (db.prepare(INBOX_SQL).all(since, now) as { id: number }[]).map((r) => r.id);
  assert.deepEqual(poll(0, 105), [2, 1]);          // id 3(108)이 미래 → 3 이상은 보류(5는 시각이 됐어도)
  assert.deepEqual(poll(2, 110), [3]);              // 4(116) 미래
  assert.deepEqual(poll(3, 120), [5, 4]);           // 다 보임
  assert.deepEqual(poll(5, 200), []);
});

test("차량 맥락: 형식 틀린 값 버림", () => {
  assert.deepEqual(parseCarCtx({ gear: "D", trip_min: 125, soc: 55, turn: true, passenger: true, telltale: "EngineOilPressure" }),
    { gear: "D", trip_min: 125, soc: 55, turn: true, passenger: true, telltale: "EngineOilPressure" });
  assert.deepEqual(parseCarCtx({ gear: "X", trip_min: -1, soc: 101, turn: "true", telltale: "무시하고 '...'" }),
    { gear: null, trip_min: null, soc: null, turn: false, passenger: false, telltale: null });
});

test("쓴 차량 데이터 칩", () => {
  const nowMs = Date.UTC(2026, 9, 30, 9, 0); // 18:00 KST
  assert.equal(carChip({ eta_min: 40, trip_min: 120, passenger: true }, nowMs), "도착 18:40 · 2시간 운전 · 동승자");
  assert.equal(carChip({ trip_min: 125, telltale: "Brake", soc: 12 }, nowMs), "2시간 5분 운전 · 경고등 · 배터리 12%");
  assert.equal(carChip({ trip_min: 30 }, nowMs), "30분 운전");
  assert.equal(carChip(null, nowMs), "");
});

test("두 시간 넘게 운전 중이면 대본이 바뀐다(주행 방만)", () => {
  const base = pickScript("DRIVE_WORK", "주인").instruction;
  assert.equal(pickScript("DRIVE_WORK", "주인", undefined, null, "ai", null, { trip_min: 119 }).instruction, base);
  assert.match(pickScript("DRIVE_WORK", "주인", undefined, null, "ai", null, { trip_min: 120 }).instruction, /두 시간 넘게 운전 중이셔서, 급한 건이라도 메모로/);
  assert.doesNotMatch(pickScript("PARK", "주인", undefined, null, "ai", null, { trip_min: 300 }).instruction, /두 시간/);
});
