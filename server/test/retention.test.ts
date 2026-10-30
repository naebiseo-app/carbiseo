import test from "node:test";
import assert from "node:assert/strict";
import { retention } from "../src/index.ts";

test("보관 정책: 원문 7일, 행 30일", () => {
  const t = 100 * 86_400_000;
  const [[raw, rawCut], [del, delCut]] = retention(t);
  assert.match(raw, /SET raw=NULL/); assert.equal(rawCut, 93 * 86_400_000);
  assert.match(del, /^DELETE FROM inbox/); assert.equal(delCut, 70 * 86_400_000);
});
