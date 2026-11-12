import test from "node:test";
import assert from "node:assert/strict";
import { nearby } from "../src/index.ts";

const real = globalThis.fetch;
const mock = (documents: unknown[], ok = true) => { globalThis.fetch = (async () => ({ ok, json: async () => ({ documents }) })) as unknown as typeof fetch; };
test.afterEach(() => { globalThis.fetch = real; });

test("성공: 결과를 숫자로 바꿔 가까운 순 그대로 돌려준다", async () => {
  mock([{ place_name: "가명 식당", y: "37.41", x: "127.11", distance: "820" }, { place_name: "가명 분식", y: "37.42", x: "127.12", distance: "1500" }]);
  assert.deepEqual(await nearby("k", "FD6", "37.4", "127.1"), { places: [{ name: "가명 식당", lat: 37.41, lng: 127.11, distance: 820 }, { name: "가명 분식", lat: 37.42, lng: 127.12, distance: 1500 }] });
});
test("충전소(CS2) 허용, 15곳까지만. 편의점 카테고리가 아니라 전기차충전소 키워드로 찾는다", async () => {
  let url = "";
  globalThis.fetch = (async (u: string) => { url = u; return { ok: true, json: async () => ({ documents: Array.from({ length: 20 }, (_, i) => ({ place_name: `충전${i}`, y: "37.4", x: "127.1", distance: String(i) })) }) }; }) as unknown as typeof fetch;
  const r = await nearby("k", "CS2", "37.4", "127.1");
  assert.equal("places" in r && r.places.length, 15);
  assert.ok(url.includes("keyword.json") && url.includes(encodeURIComponent("전기차충전소")) && !url.includes("category_group_code"));
});
test("허용 밖 category·잘못된 좌표: bad_request(호출 안 함)", async () => {
  globalThis.fetch = (async () => { throw new Error("called"); }) as unknown as typeof fetch;
  assert.deepEqual(await nearby("k", "HP8", "37.4", "127.1"), { reason: "bad_request" });
  assert.deepEqual(await nearby("k", "FD6", "x", "127.1"), { reason: "bad_request" });
  assert.deepEqual(await nearby("k", "FD6", "", "127.1"), { reason: "bad_request" });
});
test("키 없음: no_key(호출 안 함)", async () => {
  globalThis.fetch = (async () => { throw new Error("called"); }) as unknown as typeof fetch;
  assert.deepEqual(await nearby(undefined, "CE7", "37.4", "127.1"), { reason: "no_key" });
});
test("결과 없음: not_found, 상대 서버 오류: upstream", async () => {
  mock([]); assert.deepEqual(await nearby("k", "OL7", "37.4", "127.1"), { reason: "not_found" });
  mock([], false); assert.deepEqual(await nearby("k", "OL7", "37.4", "127.1"), { reason: "upstream" });
});
