// 공공 실시간 주차(공공데이터포털 Parking_v1). 위치는 저장·로그하지 않고, 키·URL은 로그와 응답에 내지 않는다.
export type Lot = [id: string, name: string, lat: number, lng: number]; // parking_lots.json (scripts/parking_index.py가 만든다)
export type Near = { id: string; name: string; lat: number; lng: number; dist_m: number };
export type Realtime = { prk_center_id?: string; pkfc_ParkingLots_total?: string; pkfc_Available_ParkingLots_total?: string };
export type ParkItem = { name: string; available: number; total: number; dist_m: number; lat: number; lng: number };

const R = 6_371_000;
const rad = (d: number) => (d * Math.PI) / 180;
function meters(lat1: number, lng1: number, lat2: number, lng2: number) {
  const a = Math.sin(rad(lat2 - lat1) / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(rad(lng2 - lng1) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
}

/** 반경 안(기본 1000m) 가까운 순 최대 3곳. */
export function nearbyLots(lots: Lot[], lat: number, lng: number, radius = 1000, max = 3): Near[] {
  return lots.map(([id, name, la, lo]) => ({ id, name, lat: la, lng: lo, dist_m: Math.round(meters(lat, lng, la, lo)) }))
    .filter((p) => p.dist_m <= radius).sort((a, b) => a.dist_m - b.dist_m).slice(0, max);
}

/** 근처 곳에 실시간 면수를 붙인다. 실시간 행이 없거나 전체 면수가 0이거나 빈자리가 전체보다 많은 곳은 뺀다(값은 문자열로 온다). */
export function mergeRealtime(near: Near[], rows: Realtime[]): ParkItem[] {
  const by = new Map(rows.map((r) => [r.prk_center_id, r]));
  return near.flatMap((n) => {
    const r = by.get(n.id);
    const total = Number(r?.pkfc_ParkingLots_total), available = Number(r?.pkfc_Available_ParkingLots_total);
    return total > 0 && available <= total ? [{ name: n.name, available: Math.max(0, available), total, dist_m: n.dist_m, lat: n.lat, lng: n.lng }] : [];
  });
}

/** key 없음 no_key / 근처 없음 none / API 실패 error / 정상 items. 근처 후보가 없으면 API를 부르지 않는다. */
export async function parkingNear(key: string | undefined, lots: Lot[], lat: number, lng: number): Promise<{ items: ParkItem[] } | { reason: "no_key" | "none" | "error" }> {
  if (!key) return { reason: "no_key" };
  const near = nearbyLots(lots, lat, lng);
  if (!near.length) return { reason: "none" };
  try {
    // 한계: 전체(약 4600곳)를 매번 받는다(약 1초). 느리면 짧은 캐시. 
    const r = await fetch(`https://apis.data.go.kr/B553881/Parking_v1/PrkRealtimeInfo_v1?serviceKey=${encodeURIComponent(key)}&pageNo=1&numOfRows=5000&format=2`);
    const rows = r.ok ? ((await r.json()) as { PrkRealtimeInfo?: Realtime[] }).PrkRealtimeInfo : undefined;
    if (!Array.isArray(rows)) return { reason: "error" };
    const items = mergeRealtime(near, rows);
    return items.length ? { items } : { reason: "none" };
  } catch { return { reason: "error" }; }
}
