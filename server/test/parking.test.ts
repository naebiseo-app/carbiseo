import test from "node:test";
import assert from "node:assert/strict";
import worker from "../src/index.ts";
import { mergeRealtime, nearbyLots, parkingNear, type Lot } from "../src/parking.ts";

const real = globalThis.fetch;
test.afterEach(() => { globalThis.fetch = real; });
// 가상 주차장: 기준점(37.4, 127.1)에서 위도 0.001도 = 약 111m
const LOTS: Lot[] = [["a", "가명 먼 주차장", 37.4075, 127.1], ["b", "가명 가까운 주차장", 37.401, 127.1], ["c", "가명 중간 주차장", 37.404, 127.1], ["d", "가명 반경밖", 37.42, 127.1], ["e", "가명 넷째", 37.4005, 127.1], ["f", "가명 다섯째", 37.409, 127.1]];
const rt = (id: string, total: string, avail: string) => ({ prk_center_id: id, pkfc_ParkingLots_total: total, pkfc_Available_ParkingLots_total: avail });
const mockRt = (rows: unknown, ok = true) => { globalThis.fetch = (async () => ({ ok, json: async () => ({ PrkRealtimeInfo: rows }) })) as unknown as typeof fetch; };

test("nearbyLots: 반경 1000m 안만, 거리순, 최대 3곳", () => {
  const r = nearbyLots(LOTS, 37.4, 127.1);
  assert.deepEqual(r.map((p) => p.id), ["e", "b", "c"]); // a(약 830m)는 반경 안이지만 4번째라 제외
  assert.ok(r.every((p, i) => p.dist_m <= 1000 && (i === 0 || r[i - 1].dist_m <= p.dist_m)));
  assert.equal(nearbyLots(LOTS, 37.4, 127.1, 1000, 10).length, 4); // 상한을 풀어도 반경 안은 a·b·c·e뿐(f는 1000m 살짝 밖, d는 2km 밖)
});
test("nearbyLots: 반경 밖뿐이면 빈 배열", () => assert.deepEqual(nearbyLots(LOTS, 35, 129), []));

test("mergeRealtime: 면수는 숫자로, 전체 면수 0·실시간 행 없음은 뺀다", () => {
  const near = nearbyLots(LOTS, 37.4, 127.1);
  const r = mergeRealtime(near, [rt("b", "120", "7"), rt("c", "0", "0"), rt("zzz", "5", "5")]);
  assert.deepEqual(r.map((p) => [p.name, p.available, p.total]), [["가명 가까운 주차장", 7, 120]]);
  assert.equal(typeof r[0].dist_m, "number");
});

test("parkingNear 정상: 거리순 items", async () => {
  mockRt([rt("b", "100", "0"), rt("c", "50", "12"), rt("e", "30", "3")]);
  const r = await parkingNear("k", LOTS, 37.4, 127.1);
  assert.ok("items" in r);
  assert.deepEqual(r.items.map((p) => [p.name, p.available, p.total]), [["가명 넷째", 3, 30], ["가명 가까운 주차장", 0, 100], ["가명 중간 주차장", 12, 50]]);
  assert.deepEqual(Object.keys(r.items[0]).sort(), ["available", "dist_m", "lat", "lng", "name", "total"]);
});
test("parkingNear 키 없음: no_key(호출 안 함)", async () => {
  globalThis.fetch = (async () => { throw new Error("called"); }) as unknown as typeof fetch;
  assert.deepEqual(await parkingNear(undefined, LOTS, 37.4, 127.1), { reason: "no_key" });
});
test("parkingNear 근처 없음·전부 면수 0: none (근처 없으면 호출 안 함)", async () => {
  globalThis.fetch = (async () => { throw new Error("called"); }) as unknown as typeof fetch;
  assert.deepEqual(await parkingNear("k", LOTS, 35, 129), { reason: "none" });
  mockRt([rt("b", "0", "0")]); assert.deepEqual(await parkingNear("k", LOTS, 37.4, 127.1), { reason: "none" });
});
test("parkingNear API 실패: error (HTTP 오류·예외·모양 이상)", async () => {
  mockRt([], false); assert.deepEqual(await parkingNear("k", LOTS, 37.4, 127.1), { reason: "error" });
  globalThis.fetch = (async () => { throw new Error("https://x?serviceKey=SECRET"); }) as unknown as typeof fetch;
  assert.deepEqual(await parkingNear("SECRET", LOTS, 37.4, 127.1), { reason: "error" });
  mockRt(undefined); assert.deepEqual(await parkingNear("k", LOTS, 37.4, 127.1), { reason: "error" });
});

// 라우트: 인증·좌표 검증·no_key (동봉된 parking_lots.json은 지금 빈 배열)
const env = (more: object = {}) => ({ CAR_TOKEN: "t", ...more }) as any;
const get = (q: string, e: any, auth = "Bearer t") => worker.fetch(new Request(`https://x/car/parking${q}`, { headers: { authorization: auth } }), e);
test("GET /car/parking: 401, 좌표 오류 400, 키 없음 no_key, 키 있으면 빈 색인은 none", async () => {
  assert.equal((await get("?lat=37.4&lng=127.1", env(), "Bearer x")).status, 401);
  for (const q of ["", "?lat=37.4", "?lat=x&lng=127.1", "?lat=95&lng=127.1", "?lat=37.4&lng=200", "?lat=&lng="]) assert.equal((await get(q, env({ PARKING_API_KEY: "k" }))).status, 400, q);
  assert.deepEqual(await (await get("?lat=37.4&lng=127.1", env())).json(), { reason: "no_key" });
  assert.deepEqual(await (await get("?lat=37.4&lng=127.1", env({ PARKING_API_KEY: "k" }))).json(), { reason: "none" });
});

test("mergeRealtime: 빈자리가 전체보다 많은 잘못된 값은 뺀다", () => {
  const near = [{ id: "a", name: "가명", lat: 0, lng: 0, dist_m: 1 }];
  assert.deepEqual(mergeRealtime(near, [{ prk_center_id: "a", pkfc_ParkingLots_total: "10", pkfc_Available_ParkingLots_total: "12" }]), []);
});
