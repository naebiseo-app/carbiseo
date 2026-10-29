import test from "node:test";
import assert from "node:assert/strict";
import { pickScript, parseRoom } from "../src/script.ts";

test("방별 대본 선택", () => {
  assert.match(pickScript("DRIVE_WORK").instruction, /운전 중/);
  assert.match(pickScript("DRIVE_REST").instruction, /내일 오전/);
  assert.match(pickScript("PARK").instruction, /자리를 비우/);
  assert.equal(pickScript(null).room, "DRIVE_WORK");
  assert.equal(parseRoom("FLY"), null);
  for (const r of ["PARK", "DRIVE_WORK", "DRIVE_REST"] as const)
    assert.match(pickScript(r).instruction, /약속이나 일정을 확정하지 않고/);
});
