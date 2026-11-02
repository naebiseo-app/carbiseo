import test from "node:test";
import assert from "node:assert/strict";
import { placePhone } from "../src/index.ts";

const real = globalThis.fetch;
const mock = (documents: unknown[], ok = true) => { globalThis.fetch = (async () => ({ ok, json: async () => ({ documents }) })) as unknown as typeof fetch; };
test.afterEach(() => { globalThis.fetch = real; });

test("키 없음: no_key로 조용히 실패(호출도 안 함)", async () => {
  globalThis.fetch = (async () => { throw new Error("called"); }) as unknown as typeof fetch;
  assert.deepEqual(await placePhone(undefined, "판교 한정식", "37.4", "127.1"), { phone: null, kind: null, name: null, reason: "no_key" });
});
test("결과 없음·번호 빈 문자열: not_found", async () => {
  mock([]); assert.equal((await placePhone("k", "가명 식당", "37.4", "127.1")).reason, "not_found");
  mock([{ phone: "", place_name: "가명 식당" }]); assert.equal((await placePhone("k", "가명 식당", "37.4", "127.1")).phone, null);
});
test("휴대폰 번호는 mobile", async () => {
  mock([{ phone: "010-0000-0000", place_name: "가명 식당" }]);
  assert.deepEqual(await placePhone("k", "가명 식당", "37.4", "127.1"), { phone: "010-0000-0000", kind: "mobile", name: "가명 식당" });
});
test("유선 번호는 landline", async () => {
  mock([{ phone: "02-0000-0000", place_name: "가명 식당" }]);
  assert.equal((await placePhone("k", "가명 식당", "37.4", "127.1")).kind, "landline");
});
test("상대 서버 오류·잘못된 좌표는 null", async () => {
  mock([], false); assert.equal((await placePhone("k", "가명", "37.4", "127.1")).reason, "upstream");
  assert.equal((await placePhone("k", "가명", "x", "127.1")).reason, "bad_request");
});
