import { demoSchedule, type DemoEvent } from "./admin.ts";
import { kvGet, kvPut, numberFor } from "./index.ts";

export const GOOGLE_CLIENT_ID = "859657362107-5v6l2h94epvjabej05s7efl150jk0jej.apps.googleusercontent.com"; // 웹 클라이언트 ID는 공개값
const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SYNC_GAP = 5 * 60_000;

type Env = { DB: D1Database; GOOGLE_CLIENT_SECRET?: string; KAKAO_REST_KEY?: string };
type Google = { refresh_token: string; synced_at?: number; tried_at?: number; expired?: boolean };

/** 구글 일정 → demoSchedule 모양. 취소 제외, 제목 없으면 "일정", 종일은 KST 자정. 장소는 "대한민국"·쉼표 뒤 주소를 뗀다. 연락처는 나 아닌 첫 참석자 이름. 좌표·번호는 fillEvents가 채운다. */
export function toEvents(items: any[]): DemoEvent[] {
  return (Array.isArray(items) ? items : []).flatMap((e) => {
    if (!e || e.status === "cancelled") return [];
    const start = e.start?.dateTime ? Date.parse(e.start.dateTime) : e.start?.date ? Date.parse(`${e.start.date}T00:00:00+09:00`) : NaN;
    if (!Number.isFinite(start)) return [];
    const guest = String((e.attendees ?? []).find((a: any) => !a?.self && a?.displayName)?.displayName ?? "");
    return [{ title: String(e.summary ?? "").trim() || "일정", start, place: String(e.location ?? "").replace(/대한민국\s*/g, "").split(",")[0].trim(), lat: 0, lng: 0, contact: guest, contact_number: "" }];
  });
}

// 이름만으로 어디인지 알 수 없는 장소는 찾지 않는다(엉뚱한 곳으로 지각 경고가 나간다)
const VAGUE = /^(집|우리집|본가|회사|사무실|학교|온라인|화상|줌|zoom|https?:.*)$/i;

/** 장소 이름 → 좌표. 카카오 키워드 검색, 없으면 주소 검색. 못 찾으면 null(차는 좌표 0인 일정의 지각 계산을 건너뛴다). */
// 한계: 차 위치 없이 전국 검색이라 "스타벅스"는 아무 지점이 나온다. 구글 장소 자동완성을 쓰면 주소가 와서 괜찮다.
export async function placeCoord(key: string | undefined, place: string): Promise<{ lat: number; lng: number } | null> {
  if (!key || !place || VAGUE.test(place)) return null;
  for (const api of ["keyword", "address"]) {
    try {
      const r = await fetch(`https://dapi.kakao.com/v2/local/search/${api}.json?query=${encodeURIComponent(place)}&size=1`, { headers: { Authorization: `KakaoAK ${key}` } });
      const d = r.ok ? ((await r.json()) as { documents?: { x?: string; y?: string }[] }).documents?.[0] : null;
      const lat = Number(d?.y), lng = Number(d?.x);
      if (d && lat && lng) return { lat, lng };
    } catch { /* 다음 검색으로 */ }
  }
  return null;
}

/** 좌표(지난 동기화에 찾은 장소는 재사용)와 참석자 번호를 채운다. */
async function fillEvents(env: Env, events: DemoEvent[]): Promise<DemoEvent[]> {
  const old: DemoEvent[] = (await kvGet(env.DB, "schedule")) ?? [];
  return Promise.all(events.map(async (e) => {
    const c = old.find((o) => o.place === e.place && o.lat) ?? await placeCoord(env.KAKAO_REST_KEY, e.place);
    return { ...e, lat: c?.lat ?? 0, lng: c?.lng ?? 0, contact_number: e.contact ? (await numberFor(env, e.contact)) ?? "" : "" };
  }));
}

async function tokenPost(env: Env, params: Record<string, string>): Promise<{ ok: boolean; body: any }> {
  const r = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ ...params, client_id: GOOGLE_CLIENT_ID, client_secret: env.GOOGLE_CLIENT_SECRET ?? "" }),
  });
  return { ok: r.ok, body: await r.json().catch(() => ({})) };
}

/** 폰이 준 인증 코드를 refresh_token으로 바꿔 저장하고 바로 동기화. 토큰은 밖으로 내지 않는다. */
export async function connectGoogle(env: Env, code: string, nowMs: number): Promise<{ status: number; body: object }> {
  if (!env.GOOGLE_CLIENT_SECRET) return { status: 503, body: { error: "no_secret" } };
  const r = await tokenPost(env, { grant_type: "authorization_code", code, redirect_uri: "" }).catch(() => null);
  const rt = r?.ok ? r.body.refresh_token : null;
  if (typeof rt !== "string" || !rt) return { status: 400, body: { error: "exchange_failed" } };
  await kvPut(env.DB, "google", { refresh_token: rt });
  await syncCalendar(env, nowMs);
  return { status: 200, body: { ok: true } };
}

export async function googleStatus(env: Env) {
  const g: Google | null = await kvGet(env.DB, "google");
  return { connected: !!g, synced_at: g?.synced_at ?? null, expired: !!g?.expired };
}

/** 연결 끊기: 토큰 revoke는 시도만 하고, 일정은 데모로 되돌린다. */
export async function disconnectGoogle(env: Env, nowMs: number) {
  const g: Google | null = await kvGet(env.DB, "google");
  if (g) await fetch("https://oauth2.googleapis.com/revoke", { method: "POST", headers: { "content-type": "application/x-www-form-urlencoded" }, body: new URLSearchParams({ token: g.refresh_token }) }).catch(() => {});
  await env.DB.prepare("DELETE FROM kv WHERE key='google'").run();
  await kvPut(env.DB, "schedule", demoSchedule(nowMs));
}

/** 구글 일정 7일치를 kv "schedule"로. 연결이 없거나 실패하면 기존 일정을 그대로 둔다. */
export async function syncCalendar(env: Env, nowMs: number): Promise<void> {
  const g: Google | null = await kvGet(env.DB, "google");
  if (!g || g.expired) return;
  await kvPut(env.DB, "google", { ...g, tried_at: nowMs });
  try {
    const t = await tokenPost(env, { grant_type: "refresh_token", refresh_token: g.refresh_token });
    if (!t.ok) {
      if (t.body?.error === "invalid_grant") await kvPut(env.DB, "google", { ...g, tried_at: nowMs, expired: true }); // 시험 모드 토큰 7일 만료
      return;
    }
    const q = new URLSearchParams({ timeMin: new Date(nowMs).toISOString(), timeMax: new Date(nowMs + 7 * 86_400_000).toISOString(), singleEvents: "true", orderBy: "startTime", maxResults: "20" });
    const r = await fetch(`https://www.googleapis.com/calendar/v3/calendars/primary/events?${q}`, { headers: { authorization: `Bearer ${t.body.access_token}` } });
    if (!r.ok) return;
    await kvPut(env.DB, "schedule", await fillEvents(env, toEvents(((await r.json()) as any).items)));
    await kvPut(env.DB, "google", { ...g, tried_at: nowMs, synced_at: nowMs });
  } catch { /* 기존 일정 유지 */ }
}

/** 마지막 시도가 5분 넘었을 때만 동기화(요청이 몰려도 구글 호출은 5분에 1번). 실패해도 던지지 않는다. */
export async function syncIfStale(env: Env, nowMs: number): Promise<void> {
  const g: Google | null = await kvGet(env.DB, "google");
  if (g && nowMs - (g.tried_at ?? 0) >= SYNC_GAP) await syncCalendar(env, nowMs).catch(() => {});
}
