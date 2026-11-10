import test from "node:test";
import assert from "node:assert/strict";
import { hotwords, WORK_WORDS } from "../src/sttdict.ts";

test("받아쓰기 핫워드: 일정·연락처 낱말 + 업무 낱말, 한 글자·특수문자 제외", () => {
  const h = hotwords({ 이지은: "x", "우리딸 ❤": "y" }, [{ title: "한빛물산 납품 미팅", place: "판교역", contact: "김민수 과장" }]);
  for (const w of ["이지은", "우리딸", "한빛물산", "납품", "판교역", "김민수", "과장", "견적서"]) assert.ok(h.includes(w), w);
  assert.ok(!h.includes("❤"));
  assert.equal(new Set(h).size, h.length);
  assert.deepEqual(hotwords(null, null), WORK_WORDS);
});
