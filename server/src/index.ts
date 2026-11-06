import { CALL_ID, carChip, callerNumber, cleanMent, cleanName, field, freshCtx, hhmm, isUrgent, nextEventAt, ownerScript, parseCarCtx, parseEta, parseMode, parseRoom, pickScript, isDirect, sameNumber, type Mode, type Recent } from "./script.ts";
import { adminPage, basicOk, demoSchedule, encodeReplay, parseFakeCall, replayScript, type MailRow, type ReplyRow } from "./admin.ts";
import { FALLBACK, guardText, isBlocked } from "./guard.ts";
import { parseNotify, parseReply, TAKE_REPLIES_SQL, type Notif } from "./notif.ts";

interface Env {
  DB: D1Database;
  CAR_TOKEN: string;
  CALL_CONTEXT_SECRET: string;
  CLAWOPS_SIGNING_KEY?: string; // 아직 없으면 서명 검증은 'nokey'로 기록만
  ADMIN_TOKEN?: string; // 없으면 /admin 전부 401
  CLAWOPS_ACCOUNT_ID?: string;
  CLAWOPS_API_KEY?: string;
  CLAWOPS_NUMBER?: string; // 문자 발신 070
  OWNER_NUMBER?: string; // 주인 휴대폰: 이 번호로 오면 주인 모드
  SMS_RELAY_URL?: string; // 문자 중계(Vercel 서울, sms-relay/api/send.js)
  SMS_RELAY_SECRET?: string;
  ANTHROPIC_API_KEY?: string; // 말동무 대화(/car/chat). 없으면 no_key 고정 문구
  KAKAO_REST_KEY?: string; // 카카오 로컬 키워드 검색(가게 번호). 없으면 place-phone은 no_key
}

const json = (o: unknown, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8" } });

const now = () => Date.now();

async function kvGet(db: D1Database, key: string) {
  const r = await db.prepare("SELECT value FROM kv WHERE key=?").bind(key).first<{ value: string }>();
  return r ? JSON.parse(r.value) : null;
}

async function saveInbox(db: D1Database, kind: string, status: string, room: string | null, raw: string, meta: object) {
  await db
    .prepare("INSERT INTO inbox(kind,status,room,created_at,raw,meta) VALUES(?,?,?,?,?,?)")
    .bind(kind, status, room, now(), raw, JSON.stringify(meta))
    .run();
}

const b64 = (b: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(b)));

// ClawOps 서명(문서 platform.claw-ops.com/docs/webhooks/signature-verification, 10-29 확인):
// base64(HMAC-SHA256(key, 웹훅URL + 파라미터를 키 사전순으로 key+value 이어붙인 것)). 폼이 아니면 JSON 최상위 값으로 같은 규칙.
export function signData(url: string, ct: string | null, raw: string): string {
  let params: [string, string][] = [];
  if ((ct ?? "").includes("json")) {
    try { params = Object.entries(JSON.parse(raw)).map(([k, v]) => [k, typeof v === "string" ? v : JSON.stringify(v)]); } catch { /* 빈 파라미터 */ }
  } else params = [...new URLSearchParams(raw)];
  params.sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0));
  return url + params.map(([k, v]) => k + v).join("");
}

// 결과 = ok | fail | nokey | nosig. ponytail: 아직 기록만 하고 거절하지 않음 — 실제 이벤트로 ok 확인 후 fail을 401로.
async function checkSig(env: Env, url: string, ct: string | null, raw: string, sig: string | null) {
  if (!env.CLAWOPS_SIGNING_KEY) return "nokey";
  if (!sig) return "nosig";
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.CLAWOPS_SIGNING_KEY), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(signData(url, ct, raw)));
  return sig.trim() === b64(mac) ? "ok" : "fail";
}

/** summary.completed 웹훅(폼) → 차 카드. 받은 전화(inbound)만. 번호는 끝 4자리만 남긴다. */
export function summaryCard(raw: string) {
  const f = new URLSearchParams(raw);
  if (f.get("Event") !== "summary.completed" || f.get("Direction") !== "inbound") return null;
  let j: { coreSummary?: string; decisions?: string[]; followUps?: string[] } = {};
  try { j = JSON.parse(f.get("SummaryJson") ?? "{}"); } catch { /* 빈 요약 */ }
  const summary = (j.coreSummary ?? "").slice(0, 300);
  const urgent = isUrgent([summary, ...(j.decisions ?? []), ...(j.followUps ?? [])].join(" ")) ? 1 : 0;
  return { call_id: f.get("CallId") ?? "", ...fromCols(f.get("From") ?? ""), summary, urgent };
}

// H15: 회신용 전체 번호도 보관(행은 30일 뒤 삭제, retention). 화면·라벨엔 끝 4자리만.
const fromCols = (n: string) => {
  const d = n.replace(/\D/g, "");
  return { from_label: d.length >= 4 ? `끝번호 ${d.slice(-4)}` : "", from_number: /^\d{9,11}$/.test(d) ? d : null };
};

type Card = ReturnType<typeof fromCols> & { call_id: string; summary: string; urgent: number };
// 같은 통화의 카드는 1장만: 웹훅 재전송, report_call 뒤 오는 summary.completed는 건너뛴다
// ponytail: 칩은 카드 만든 시점의 차량 맥락. 정확히 하려면 call-context 때 값을 ring meta에 남겨 call_id로 잇기
// from = 발신번호 원문. 바로 연결 목록(settings.direct_numbers)에 있으면 urgent=1·meta.direct (차는 피로·퇴근 중에도 읽음)
async function saveCallCard(db: D1Database, c: Card, extra: object = {}, from = "", status = "summarized") {
  c = { ...c, summary: guardText("summary", c.summary) }; // LLM 가드: 요약 카드 저장은 전부 여기를 지난다
  const cc = await kvGet(db, "car_context");
  if (isDirect((await kvGet(db, "settings"))?.direct_numbers, from)) { c = { ...c, urgent: 1 }; extra = { ...extra, direct: true }; }
  // 바로 연결 번호는 전화 오는 순간 status='ringing' 카드가 먼저 있다. 차는 id>since만 받아 같은 행의 변경을 못 보므로,
  // 요약이 오면 새 id로 summarized 행을 넣고(meta.replaces=ringing 행 id) ringing 행은 지운다. 차가 replaces id 카드를 뺀다.
  const ringId = status === "summarized"
    ? (await db.prepare("SELECT id FROM inbox WHERE kind='call' AND call_id=? AND status='ringing' LIMIT 1").bind(c.call_id).first<{ id: number }>())?.id : undefined;
  const meta = JSON.stringify({ car: carChip(cc, now()), ...extra, ...(ringId ? { replaces: ringId } : {}) });
  await db.prepare("INSERT INTO inbox(kind,status,room,call_id,from_label,from_number,summary,urgent,created_at,meta) SELECT 'call',?,?,?,?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM inbox WHERE kind='call' AND call_id=? AND (?='ringing' OR status<>'ringing'))")
    .bind(status, parseRoom(cc?.room) ?? null, c.call_id, c.from_label, c.from_number, c.summary, c.urgent, now(), meta, c.call_id, status).run();
  if (ringId) await db.prepare("DELETE FROM inbox WHERE id=?").bind(ringId).run();
}

// 도구 결과도 AI가 읽는다: 호출 직후 AI가 통화를 끊은 일이 있어(11-06 실통화) 끊지 말라고 돌려준다
const AFTER_REPORT = "기록했습니다. 지금은 아무 말도 하지 않습니다. 통화를 끊지 말고 상대가 끊을 때까지 기다립니다. 상대가 말하면 이어서 답합니다.";

const SUMMARY_SYSTEM = "전화 받아쓰기를 운전자가 볼 메모로 정리합니다. 받아쓰기에 있는 내용만 쓰고, 시각은 상대가 말한 그대로 씁니다. '아, 네' 같은 맞장구는 이름이 아니고, 비서가 되읽어 상대가 고친 이름이 있으면 고친 이름을 씁니다. JSON 한 줄만 답합니다: {\"caller\":\"상대 이름·소속, 없으면 빈 문자열\",\"summary\":\"누가 무슨 일로 언제까지 연락을 원하는지와 전해 달라는 내용을 모두 담은 한두 문장\",\"callback_by\":\"연락 희망 시각, 없으면 빈 문자열\"}";

// 받아쓰기 → 메모. 실패는 null(호출한 쪽이 상대 말 그대로 쓴다)
async function summarizeCall(key: string | undefined, dialog: string): Promise<{ caller?: string; summary?: string; callback_by?: string } | null> {
  if (!key) return null;
  try {
    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({ model: "claude-haiku-5-5", max_tokens: 300, thinking: { type: "disabled" }, system: SUMMARY_SYSTEM, messages: [{ role: "user", content: dialog }] }),
      signal: AbortSignal.timeout(10000),
    });
    if (!r.ok) return null;
    const d = (await r.json()) as { content?: { type?: string; text?: string }[] };
    return JSON.parse(d.content?.find((b) => b.type === "text")?.text?.match(/\{[\s\S]*\}/)?.[0] ?? "null");
  } catch { return null; }
}

// 손님 통화 카드는 끊은 뒤 받아쓰기로 만든다. 통화 중 도구 호출은 답을 7초 늦추고 말을 두 번 하게 했다(11-06 실통화), summary.completed는 안 온다(12통 전부 not_requested)
async function transcriptCard(env: Env, raw: string) {
  const f = new URLSearchParams(raw), id = f.get("CallId") ?? "", from = f.get("From") ?? "";
  if (f.get("Event") !== "transcript.completed" || f.get("Direction") !== "inbound" || !env.CLAWOPS_API_KEY) return;
  if (sameNumber(from, env.OWNER_NUMBER) && !(await kvGet(env.DB, "owner_as_guest"))) return;
  if (await env.DB.prepare("SELECT 1 FROM inbox WHERE kind='call' AND call_id=? AND status<>'ringing'").bind(id).first()) return;
  const r = await fetch(`https://api.claw-ops.com/v1/accounts/${env.CLAWOPS_ACCOUNT_ID}/calls/${id}/transcript`, { headers: { Authorization: `Bearer ${env.CLAWOPS_API_KEY}` } });
  const segs = ((await r.json()) as { segments?: { speaker: string; text: string }[] }).segments ?? [];
  const ai = segs[0]?.speaker; // 첫 화자 = AI 소개
  const said = segs.filter((s) => s.speaker !== ai).map((s) => s.text).join(" ");
  const memo = said ? await summarizeCall(env.ANTHROPIC_API_KEY, segs.map((s) => `${s.speaker === ai ? "비서" : "상대"}: ${s.text}`).join("\n")) : null;
  const summary = memo?.summary || (said ? `상대 말: ${said}` : "말씀 없이 끊긴 전화예요");
  const cols = fromCols(from), who = String(memo?.caller ?? "").replace(/["'`{}]/g, "").slice(0, 40);
  await saveCallCard(env.DB, { call_id: id, ...cols, from_label: who || cols.from_label, summary, urgent: isUrgent(`${summary} ${said}`) ? 1 : 0 },
    { via: "transcript", caller: who, callback_by: String(memo?.callback_by ?? "").slice(0, 50) }, from);
}

// MCP report_call: AI가 끝맺음 뒤에 요약을 보낸다 → 끊는 순간 카드. 결과 "ok" 또는 오류 문구.
export async function reportCall(env: Env, a: Record<string, unknown>): Promise<string> {
  const id = String(a.call_id ?? "");
  if (!CALL_ID.test(id)) return "bad call_id";
  const s = (k: string, max: number) => (typeof a[k] === "string" ? (a[k] as string).trim().slice(0, max) : "");
  // 발신번호는 AI 입력이 아니라 그 통화의 ring 행(call-context 원문)에서. id는 위 검사로 영숫자만이라 LIKE에 그대로 넣어도 된다
  const ring = await env.DB.prepare("SELECT raw FROM inbox WHERE kind='ring' AND raw LIKE ? ORDER BY id DESC LIMIT 1").bind(`%${id}%`).first<{ raw: string }>();
  const from = callerNumber(ring?.raw ?? "");
  if (sameNumber(from, env.OWNER_NUMBER) && !(await kvGet(env.DB, "owner_as_guest"))) return "ok"; // 주인 통화는 기존 할 일 경로
  const summary = (s("summary", 300) || [s("caller", 100), s("purpose", 200)].filter(Boolean).join(", ")).slice(0, 300);
  // 카드 이름표는 AI가 확인받은 이름(없으면 끝번호) → 아는 번호 확인·"문자 먼저 보낼까요"·낭독이 이름을 쓴다
  const cols = fromCols(from); const who = s("caller", 40).replace(/["'`{}]/g, "");
  // 끝맺음 뒤 내용이 늘어 다시 호출(같은 내용 재전송은 건너뜀): 앞 카드를 ringing으로 돌려 saveCallCard의 교체(새 id + meta.replaces) 경로를 탄다
  await env.DB.prepare("UPDATE inbox SET status='ringing' WHERE kind='call' AND call_id=? AND status='summarized' AND summary<>?").bind(id, summary).run();
  await saveCallCard(env.DB, { call_id: id, ...cols, from_label: who || cols.from_label, summary, urgent: a.urgent === true || isUrgent(summary) ? 1 : 0 },
    { via: "mcp", caller: s("caller", 100), purpose: s("purpose", 200), callback_by: s("callback_by", 50) }, from);
  console.log("report_call", id);
  return "ok";
}

const STR = { type: "string" };
const REPORT_TOOL = {
  name: "report_call",
  description: "끝맺음 말을 마친 뒤 말없이 호출. 그 뒤 전할 내용이 늘면 전체를 합쳐 다시 호출. 이 통화에서 들은 내용만 보낸다(지어내지 않음).",
  inputSchema: {
    type: "object", required: ["call_id"],
    properties: {
      call_id: { ...STR, description: "대본에 적힌 이 통화의 call_id" },
      caller: { ...STR, description: "통화에서 되읽은 이름·소속 그대로. '아, 네' 같은 맞장구는 넣지 않는다" }, purpose: { ...STR, description: "용건" },
      urgent: { type: "boolean", description: "급하다고 했으면 true" },
      callback_by: { ...STR, description: "상대가 말한 회신 시각 그대로, 없으면 빈 문자열" },
      summary: { ...STR, description: "이름·용건·상대가 말한 시각을 들은 그대로 담은 한두 문장 한국어 요약" },
    },
  },
};

// 최소 MCP streamable HTTP(JSON-RPC 2.0, 단일 JSON 응답). 배치·SSE·세션은 안 씀
async function mcp(req: Request, env: Env): Promise<Response> {
  const msg = (await req.json().catch(() => null)) as { id?: unknown; method?: unknown; params?: any } | null;
  if (!msg || typeof msg.method !== "string") return json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "parse error" } }, 400);
  if (msg.id === undefined) return new Response(null, { status: 202 }); // 알림(notifications/initialized 등)
  const ok = (result: unknown) => json({ jsonrpc: "2.0", id: msg.id, result });
  const p = msg.params ?? {};
  if (msg.method === "initialize") return ok({ protocolVersion: p.protocolVersion ?? "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "carbiseo", version: "1.0.0" } });
  if (msg.method === "ping") return ok({});
  if (msg.method === "tools/list") return ok({ tools: [] }); // 카드는 통화 뒤 받아쓰기로. ClawOps 연결은 PATCH가 막혀 있어 도구 목록을 비운다
  if (msg.method === "tools/call") {
    // ClawOps가 MCP 요청마다 X-ClawOps-Call-Id를 싣는다(docs voice-agent/call-variables) → AI가 옮겨 적은 값보다 우선
    const hdr = req.headers.get("x-clawops-call-id") ?? "";
    const args = { ...(p.arguments ?? {}), ...(CALL_ID.test(hdr) ? { call_id: hdr } : {}) };
    const text = p.name === "report_call" ? await reportCall(env, args) : "unknown tool";
    return ok({ content: [{ type: "text", text: text === "ok" ? AFTER_REPORT : text }], isError: text !== "ok" });
  }
  return json({ jsonrpc: "2.0", id: msg.id, error: { code: -32601, message: "method not found" } });
}

/** 주인 모드 통화 요약 → 할 일 카드 본문들: 후속 조치(요청)마다 1장. 첫 비메일 요청 앞에만 핵심 요약을 붙이고, '메일:' 줄은 따로 둬 120자 가드가 잘라도 다른 요청에 섞이지 않는다. */
export function taskTexts(raw: string): string[] {
  let j: { coreSummary?: string; followUps?: string[] } = {};
  try { j = JSON.parse(new URLSearchParams(raw).get("SummaryJson") ?? "{}"); } catch { /* 빈 요약 */ }
  const sum = j.coreSummary ?? "";
  const fus = (j.followUps ?? []).filter(Boolean);
  if (!fus.length) return sum ? [sum.slice(0, 500)] : [];
  let used = !sum;
  return fus.map((f) => {
    if (used || /^메일\s*:/.test(f)) return f.slice(0, 500);
    used = true;
    return `${sum} / ${f}`.slice(0, 500);
  });
}
export const taskText = (raw: string) => taskTexts(raw).join(" / ");

/** /car/send 본문 검증(중계와 같은 한도). 수신번호 숫자 10~11자리, 내용 1~1000자, confirmed=true 필수. */
// 설계 29: 차 카드 버튼 결과(완료·내일 아침에)를 서버 행에도 남긴다. 경로 /car/inbox/<id>, 본문 {action}
export function parseInboxAction(path: string, b: Record<string, unknown>): { id: number; status: string } | null {
  const id = Number(path.slice("/car/inbox/".length));
  const status = ({ done: "done", tomorrow: "tomorrow" } as Record<string, string>)[String(b.action)];
  return Number.isInteger(id) && id > 0 && status ? { id, status } : null;
}

export function parseSend(b: Record<string, unknown>) {
  const to = String(b.to_number ?? "").replace(/-/g, "");
  const body = typeof b.body === "string" ? b.body.trim() : "";
  if (b.confirmed !== true) return { error: "confirmed must be true" };
  if (!/^\d{10,11}$/.test(to)) return { error: "bad to_number" };
  if (body.length < 1 || body.length > 1000) return { error: "bad body" };
  if (isBlocked(body, false)) return { error: "blocked body" }; // 사용자가 확인한 문장이라 자르지 않고 금칙어만 막는다
  return { to, body, label: cleanName(b.to_label) ?? `끝번호 ${to.slice(-4)}` };
}

// 문자 발송: Workers는 국외 IP라 ClawOps가 막는다 → 서울 중계로 {to, body}. 중계는 ClawOps 응답을 그대로 돌려준다.
// 결과 code = ClawOps status(queued 등) | ClawOps code | 중계 error(unauthorized·bad_request) | http_xxx | relay_unreachable
export async function relaySend(env: Pick<Env, "SMS_RELAY_URL" | "SMS_RELAY_SECRET">, to: string, body: string, f: typeof fetch = fetch) {
  try {
    const r = await f(env.SMS_RELAY_URL ?? "", {
      method: "POST",
      headers: { "content-type": "application/json", "x-relay-secret": env.SMS_RELAY_SECRET ?? "" },
      body: JSON.stringify({ to, body }),
    });
    const j = (await r.json().catch(() => ({}))) as { messageId?: string; status?: string; code?: string; error?: string };
    return { ok: r.ok, http: r.status, code: r.ok ? j.status ?? "queued" : j.code ?? j.error ?? `http_${r.status}`, message_id: j.messageId ?? null };
  } catch {
    return { ok: false, http: 0, code: "relay_unreachable", message_id: null };
  }
}

/** 받은 전화의 AI 통화가 끝났으면 그 CallId. */
// 상태 통지 본문(폼 또는 JSON)에서 '끝난 통화'의 CallId. 키 대소문자 무시.
export function statusDone(raw: string): string | null {
  let o: Record<string, unknown> = {};
  try { o = JSON.parse(raw); } catch { o = Object.fromEntries(new URLSearchParams(raw)); }
  const get = (...ks: string[]) => { const hit = Object.entries(o ?? {}).find(([k]) => ks.includes(k.toLowerCase())); return hit ? String(hit[1]) : ""; };
  const st = get("callstatus", "status", "event", "statuscallbackevent").toLowerCase();
  const id = get("callid", "callsid", "call_id");
  return st.includes("completed") && /^CA[0-9a-f]{20,40}$/i.test(id) ? id : null;
}

export function transcriptTarget(raw: string) {
  const f = new URLSearchParams(raw);
  return f.get("Event") === "agent.disconnected" && f.get("Direction") !== "outbound" ? f.get("CallId") || null : null;
}

// 차 폴링: since 뒤 행 중, 아직 시각이 안 된(created_at 미래) 첫 행 앞까지만 준다.
// 미래 행보다 id가 큰 행을 먼저 주면 차가 since를 넘겨 버려 미래 행(재현 hint)을 영영 못 받는다.
// since>0: 오래된 것부터 INBOX_PAGE건(오래 끊겼다 와도 51번째부터 유실되지 않게, 다음 폴링이 이어 받음). 한 건 더 읽어 more 판정.
// since=0(차 첫 폴링·재시작): 최신 INBOX_PAGE건 — 오래된 것부터 주면 옛 카드가 새 전화처럼 읽힌다.
export const INBOX_PAGE = 50;
export const INBOX_SQL =
  "SELECT * FROM inbox WHERE id>?1 AND id < COALESCE((SELECT MIN(id) FROM inbox WHERE id>?1 AND created_at>?2), 9e18) ORDER BY CASE WHEN ?1=0 THEN -id ELSE id END LIMIT 51";

// 폰 알림 1건 → inbox kind='notif'. 방은 받을 때의 차 방(카드 칩·판단 참고용)
async function saveNotif(db: D1Database, n: Notif) {
  const room = parseRoom((await kvGet(db, "car_context"))?.room) ?? null;
  return db.prepare("INSERT INTO inbox(kind,status,room,from_label,summary,created_at,meta) VALUES('notif','new',?,?,?,?,?)")
    .bind(room, n.sender, n.text, now(), JSON.stringify({ app: n.app, replyable: n.replyable, key: n.key })).run();
}

const DAY = 86_400_000;
export const retention = (t: number): [string, number][] => [
  ["UPDATE inbox SET raw=NULL WHERE raw IS NOT NULL AND created_at < ?", t - 7 * DAY],
  ["DELETE FROM inbox WHERE created_at < ?", t - 30 * DAY],
];

const HANGUP_MS = 12_000; // 멘트(약 8초) + 연결 지연. Workers waitUntil은 응답 뒤 30초까지

/** O4: 목적지 이름+좌표 -> 가게 번호(카카오 로컬 키워드 검색, 500m 안 가까운 순 첫 결과). 휴대폰 판정은 차 sendable()과 같다(01x 10~11자리). 키 없음·실패는 조용히 null. */
export async function placePhone(key: string | undefined, name: string, lat: string, lng: string): Promise<{ phone: string | null; kind: "mobile" | "landline" | null; name: string | null; reason?: string }> {
  const none = (reason: string) => ({ phone: null, kind: null, name: null, reason });
  if (!key) return none("no_key");
  if (!name || !Number.isFinite(Number(lat)) || !Number.isFinite(Number(lng))) return none("bad_request");
  try {
    const r = await fetch(`https://dapi.kakao.com/v2/local/search/keyword.json?query=${encodeURIComponent(name)}&x=${lng}&y=${lat}&radius=500&sort=distance`, { headers: { Authorization: `KakaoAK ${key}` } });
    if (!r.ok) return none("upstream");
    const d = ((await r.json()) as { documents?: { phone?: string; place_name?: string }[] }).documents?.[0];
    const digits = (d?.phone ?? "").replace(/\D/g, "");
    if (!d || digits.length < 8) return none("not_found");
    return { phone: d.phone!.trim(), kind: /^01\d{8,9}$/.test(digits) ? "mobile" : "landline", name: d.place_name ?? name };
  } catch { return none("upstream"); }
}

const CHAT_SYSTEM = "당신은 운전자의 말동무 '내비서'입니다. 운전 중이므로 존댓말로 두 문장 이내, 60자 안으로 짧게 답합니다. 사용자가 '졸려', '피곤해'처럼 말하면 물음표가 붙어도 운전자 본인 이야기입니다. 당신이 졸린지 묻는 말로 받지 말고, 가까운 휴게소에서 쉬어 가시라고 권합니다. 편안한 잡담을 이어 가고 가끔 짧은 질문을 던져 운전자가 깨어 있게 돕습니다. 화면을 보라는 말, 목록, 이모지, 기호는 쓰지 않습니다. 의학·법률·투자 조언은 하지 않습니다. 추월·속도·차선 같은 운전 조작은 권하지 않습니다. 식당 찾기, 날씨 확인처럼 할 수 없는 일을 해 주겠다고 말하지 않습니다. 졸리다, 피곤하다는 말에는 가까운 휴게소에서 쉬기를 권합니다.";

const DROWSY = /졸려|졸리|졸음|피곤|잠와|잠온/;
const DROWSY_REPLY = "졸리시면 위험해요. 가까운 휴게소나 졸음쉼터에서 잠깐 쉬어 가세요.";

/** 말동무 답이 길면 문장 단위로 110자 안까지만 남긴다(가드 120자에 걸려 고정 문구로 바뀌지 않게). */
export function chatCut(t: string): string {
  let out = "";
  for (const s of t.match(/[^.?!]+[.?!]*\s*/g) ?? []) { if ((out + s).length > 110) break; out += s; }
  return out.trim() || t.slice(0, 110);
}

/** 말동무 대화. 대화 내용은 저장·로그하지 않는다. 키 없음 no_key, 실패·금칙어는 고정 문구 fallback. */
export async function carChat(key: string | undefined, text: string, history: { role: "user" | "assistant"; text: string }[]): Promise<{ reply: string; source: "llm" | "fallback" | "no_key" }> {
  if (DROWSY.test(text.replace(/\s/g, ""))) return { reply: DROWSY_REPLY, source: "fallback" }; // 안전 문구는 AI 답에 맡기지 않는다("저는 졸리지 않아요" 실측)
  if (!key) return { reply: FALLBACK.chat, source: "no_key" };
  const fb = { reply: FALLBACK.chat, source: "fallback" as const };
  try {
    const messages = [...history, { role: "user" as const, text }].map((h) => ({ role: h.role, content: h.text }));
    while (messages[0].role === "assistant") messages.shift(); // API는 user로 시작해야 한다
    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({ model: "claude-haiku-5-5", max_tokens: 200, thinking: { type: "disabled" }, system: CHAT_SYSTEM, messages }),
      signal: AbortSignal.timeout(6000),
    });
    if (!r.ok) return fb;
    const d = (await r.json()) as { stop_reason?: string; content?: { type?: string; text?: string }[] };
    const raw = d.content?.find((b) => b.type === "text")?.text ?? "";
    const whole = d.stop_reason === "max_tokens" ? raw.replace(/[^.?!]*$/, "") : raw; // 잘린 끝 조각만 버린다(이야기 요청은 200토큰을 넘긴다)
    const reply = guardText("chat", chatCut(whole));
    return reply === FALLBACK.chat ? fb : { reply, source: "llm" };
  } catch { return fb; }
}

export default {
  async fetch(req: Request, env: Env, ctx?: { waitUntil(p: Promise<unknown>): void }): Promise<Response> {
    const { pathname } = new URL(req.url);
    const m = req.method;
    const authed = req.headers.get("authorization") === `Bearer ${env.CAR_TOKEN}`;

    if (m === "POST" && pathname === "/car/state") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const b = (await req.json().catch(() => ({}))) as Record<string, unknown>;
      const room = parseRoom(b.room);
      if (!room) return json({ error: "room must be PARK|DRIVE_WORK|DRIVE_REST" }, 400);
      const eta_min = parseEta(b.eta_min);
      await env.DB.prepare("INSERT INTO kv(key,value,updated_at) VALUES('car_context',?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at")
        .bind(JSON.stringify({ room, eta_min, ...parseCarCtx(b) }), now()).run();
      return json({ ok: true, room, eta_min });
    }

    // 차 앱 설정: 내 호칭·AI 비서 이름
    if (m === "POST" && pathname === "/car/settings") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const b = (await req.json().catch(() => ({}))) as Record<string, unknown>;
      const cur = (await kvGet(env.DB, "settings")) ?? {};
      if ("mode" in b) {
        const v = parseMode(b.mode);
        if (!v) return json({ error: "mode must be ai|ment|off" }, 400);
        cur.mode = v;
      }
      for (const k of ["owner_label", "assistant_name"] as const) {
        if (!(k in b)) continue;
        const v = cleanName(b[k]);
        if (!v) return json({ error: `bad ${k}` }, 400);
        cur[k] = v;
      }
      if ("family_number" in b) { // 가족 도착 문자 수신번호: 숫자 10~11자리, 빈 값이면 삭제
        const v = typeof b.family_number === "string" ? b.family_number.replace(/-/g, "") : null;
        if (v === "") delete cur.family_number;
        else if (v && /^\d{10,11}$/.test(v)) cur.family_number = v;
        else return json({ error: "bad family_number" }, 400);
      }
      if ("direct_numbers" in b) { // 바로 연결 목록: 숫자 10~11자리 문자열 최대 10개, 빈 배열이면 삭제
        const a = Array.isArray(b.direct_numbers) ? b.direct_numbers.map((x) => (typeof x === "string" ? x.replace(/-/g, "") : "")) : null;
        if (a && a.length === 0) delete cur.direct_numbers;
        else if (a && a.length <= 10 && a.every((x) => /^\d{10,11}$/.test(x))) cur.direct_numbers = a;
        else return json({ error: "bad direct_numbers" }, 400);
      }
      if ("ment_text" in b) { // 응대 멘트 직접 편집: 빈 값이면 기본 문구
        const v = cleanMent(b.ment_text);
        if (v === "") delete cur.ment_text;
        else if (v) cur.ment_text = v;
        else return json({ error: "bad ment_text" }, 400);
      }
      await env.DB.prepare("INSERT OR REPLACE INTO kv(key,value,updated_at) VALUES('settings',?,?)").bind(JSON.stringify(cur), Date.now()).run();
      const out = { ...cur }; // 응답엔 끝 4자리만(차 화면 표시용)
      if (cur.family_number) out.family_number = out.family_label = `****${cur.family_number.slice(-4)}`;
      if (cur.direct_numbers) { delete out.direct_numbers; out.direct_labels = cur.direct_numbers.map((n: string) => `****${n.slice(-4)}`); }
      return json({ ok: true, settings: out });
    }

    if (m === "GET" && pathname === "/car/inbox") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const since = Number(new URL(req.url).searchParams.get("since") ?? 0) || 0;
      const rows = (await env.DB.prepare(INBOX_SQL).bind(since, now()).all()).results;
      const more = since > 0 && rows.length > INBOX_PAGE;
      const results = rows.slice(0, INBOX_PAGE);
      // min_id: 서버에 남은 가장 작은 id(비었으면 null). 차는 이보다 작은 카드를 지운다(관리자 비우기·30일 삭제 반영)
      const min = await env.DB.prepare("SELECT MIN(id) AS m FROM inbox").first<{ m: number | null }>();
      return json({ items: results, min_id: min?.m ?? null, more });
    }

    // callContextUrl: 비밀 조각 경로. 어떤 실패에도 유효한 응답을 돌려 통화가 끊기지 않게 한다.
    if (m === "POST" && pathname.startsWith("/clawops/call-context/")) {
      if (pathname.slice("/clawops/call-context/".length) !== env.CALL_CONTEXT_SECRET) return json({ error: "not found" }, 404);
      const raw = await req.text();
      let room = null as ReturnType<typeof parseRoom>;
      let owner: string | undefined;
      let name: string | undefined;
      let eta: number | null = null;
      let mode: Mode = "ai";
      let schedule = null;
      let cc: Record<string, any> | null = null; // car_context: 방·ETA·차량 맥락
      let direct = false; // 바로 연결 목록에 있는 발신자
      let ment: string | undefined;
      let known: string | undefined; // 같은 번호의 지난 통화 카드 이름(음성인식에 이름을 맡기지 않음)
      let isOwner = sameNumber(callerNumber(raw), env.OWNER_NUMBER);
      let recent: Recent[] = [];
      let clk = now(); // 대본의 요일·시각(평일/저녁/주말). 녹화용 시연 시각이 있으면 그만큼 민다
      try {
        clk += Number(await kvGet(env.DB, "demo_offset")) || 0;
        if (isOwner && (await kvGet(env.DB, "owner_as_guest"))) isOwner = false; // 녹화용 스위치: 주인 번호도 손님 대본으로
        cc = freshCtx(await env.DB.prepare("SELECT value,updated_at FROM kv WHERE key='car_context'").first<{ value: string; updated_at: number }>(), now());
        room = parseRoom(cc?.room);
        eta = parseEta(cc?.eta_min);
        const st = await kvGet(env.DB, "settings");
        owner = st?.owner_label;
        name = st?.assistant_name;
        mode = parseMode(st?.mode) ?? "ai";
        ment = st?.ment_text;
        direct = isDirect(st?.direct_numbers, callerNumber(raw));
        schedule = await kvGet(env.DB, "schedule");
        const from = callerNumber(raw);
        if (mode === "ai" && !isOwner && from && !(await kvGet(env.DB, "owner_as_guest"))) {
          // ponytail: 최근 50건만 JS에서 sameNumber로 거른다. 카드가 많아 놓치면 from_number 정규화 컬럼+인덱스로
          const rows = (await env.DB.prepare("SELECT from_label,from_number FROM inbox WHERE kind='call' AND from_label<>'' AND from_label NOT LIKE '끝번호%' AND from_number IS NOT NULL ORDER BY id DESC LIMIT 50").all<{ from_label: string; from_number: string }>()).results;
          known = rows.find((r) => sameNumber(from, r.from_number))?.from_label;
        }
        if (isOwner) recent = (await env.DB.prepare("SELECT from_label,summary,urgent FROM inbox WHERE kind='call' AND summary IS NOT NULL ORDER BY id DESC LIMIT 5").all<Recent>()).results;
        await saveInbox(env.DB, "ring", "ringing", room, raw, { ct: req.headers.get("content-type"), owner: isOwner });
      } catch (e) {
        console.log("call-context db error", String(e));
      }
      if (isOwner) {
        const instruction = ownerScript({ owner, name, room, eta, schedule, recent, nowMs: now(), passenger: cc?.passenger === true, trip_min: cc?.trip_min });
        return json({ CallContext: { Instruction: instruction, Variables: { room: room ?? "UNKNOWN", owner_mode: "1" } } });
      }
      const s = pickScript(room, owner, name, eta, mode, nextEventAt(schedule, now()), cc, clk, ment, known);
      const callId = field(raw, ["callid", "callsid", "call_id"]);
      // 멘트·끔: 모델이 끊기를 미루는 일이 있어(장면 9) 멘트 길이만큼 기다렸다가 서버가 통화를 끝낸다. ponytail: 고정 12초, 긴 사용자 멘트면 길이 비례로
      if (mode !== "ai" && CALL_ID.test(callId) && env.CLAWOPS_API_KEY)
        ctx?.waitUntil(new Promise((ok) => setTimeout(ok, HANGUP_MS)).then(() => fetch(`https://api.claw-ops.com/v1/accounts/${env.CLAWOPS_ACCOUNT_ID}/calls/${callId}`,
          { method: "POST", headers: { Authorization: `Bearer ${env.CLAWOPS_API_KEY}`, "Content-Type": "application/json" }, body: JSON.stringify({ Status: "completed" }) }))
          .then((r) => console.log("ment hangup", r.status)).catch((e) => console.log("ment hangup error", String(e))));
      // 바로 연결 번호: 통화 중에도 차가 알도록 지금 카드(ringing). 요약이 오면 saveCallCard가 같은 행을 채운다
      if (direct && CALL_ID.test(callId)) {
        const from = callerNumber(raw);
        await saveCallCard(env.DB, { call_id: callId, ...fromCols(from), summary: "바로 연결 번호에서 전화가 왔어요", urgent: 1 }, {}, from, "ringing").catch((e) => console.log("direct ring card error", String(e)));
      }
      const dl = direct && mode === "ai" ? ` 이 분은 ${owner ?? "주인"}님이 바로 연결 목록에 넣은 분입니다. 용건만 짧게 듣고 '바로 전해 드리겠습니다'로 끝냅니다.` : ""; // ment·off는 대화가 없어 생략
      return json({ CallContext: { Instruction: s.instruction + dl, Variables: { room: s.room ?? "UNKNOWN", car: carChip(cc, now()), ...(direct ? { direct: "1" } : {}) } } });
    }

    // ClawOps 에이전트 MCP 연결(report_call). 비밀은 경로에(authType none)
    if (pathname.startsWith("/mcp/")) {
      if (pathname.slice("/mcp/".length) !== env.CALL_CONTEXT_SECRET) return json({ error: "not found" }, 404);
      if (m !== "POST") return new Response(null, { status: 405, headers: { allow: "POST" } });
      return mcp(req, env);
    }

    // 번호 statusCallback: 통화가 끝나는 즉시 받아쓰기 요청 → 약 16초 뒤 summary.completed(카드). managed_agent 모드는 agent.disconnected 웹훅이 안 와서(10-31 실측) 이 경로가 주 경로
    if (m === "POST" && pathname.startsWith("/clawops/status/")) {
      if (pathname.slice("/clawops/status/".length) !== env.CALL_CONTEXT_SECRET) return json({ error: "not found" }, 404);
      const raw = await req.text();
      const id = statusDone(raw);
      await saveInbox(env.DB, "event", "status", null, raw, { ct: req.headers.get("content-type") });
      if (id && env.CLAWOPS_API_KEY) {
        const r = await fetch(`https://api.claw-ops.com/v1/accounts/${env.CLAWOPS_ACCOUNT_ID}/calls/${id}/transcript`,
          { method: "POST", headers: { Authorization: `Bearer ${env.CLAWOPS_API_KEY}` } });
        console.log("status transcript request", id, r.status);
      }
      return json({ ok: true });
    }

    if (m === "POST" && pathname === "/clawops/events") {
      const raw = await req.text();
      const sig = await checkSig(env, req.url, req.headers.get("content-type"), raw, req.headers.get("x-signature"));
      console.log("clawops webhook sig=" + sig, "ct=" + req.headers.get("content-type"));
      await saveInbox(env.DB, "event", "received", null, raw, { ct: req.headers.get("content-type"), sig, sig_header: req.headers.get("x-signature") });
      // 계정 '통화 받아쓰기'가 꺼져 있어 통화가 끝나면 받아쓰기를 직접 요청한다 → transcript.completed → summary.completed(아래 카드)
      const callId = sig === "ok" ? transcriptTarget(raw) : null;
      if (callId && env.CLAWOPS_API_KEY) {
        const r = await fetch(`https://api.claw-ops.com/v1/accounts/${env.CLAWOPS_ACCOUNT_ID}/calls/${callId}/transcript`,
          { method: "POST", headers: { Authorization: `Bearer ${env.CLAWOPS_API_KEY}` } });
        console.log("transcript request", callId, r.status); // 202 접수, 409 이미 요청됨
      }
      if (sig === "ok") await transcriptCard(env, raw).catch((e) => console.log("transcript card error", String(e)));
      const card = sig === "ok" ? summaryCard(raw) : null;
      if (card && sameNumber(new URLSearchParams(raw).get("From") ?? "", env.OWNER_NUMBER) && !(await kvGet(env.DB, "owner_as_guest"))) {
        // 주인 모드 통화 → 할 일 카드(문자 초안 등). 재전송 대비 1장만
        const cc = await kvGet(env.DB, "car_context");
        const had = await env.DB.prepare("SELECT 1 FROM inbox WHERE kind='task' AND call_id=?").bind(card.call_id).first();
        if (!had) for (const t of taskTexts(raw).length ? taskTexts(raw) : [""])
          await env.DB.prepare("INSERT INTO inbox(kind,status,room,call_id,from_label,summary,created_at) VALUES('task','open',?,?,'주인',?,?)")
            .bind(parseRoom(cc?.room) ?? null, card.call_id, guardText("summary", t), now()).run();
      } else if (card) await saveCallCard(env.DB, card, {}, new URLSearchParams(raw).get("From") ?? "");
      return json({ ok: true });
    }

    // 차 앱 '보내기' 버튼: 확인된 문자만 중계로 발송, 결과를 inbox kind='sent'로 기록
    if (m === "POST" && pathname.startsWith("/car/inbox/")) {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const p = parseInboxAction(pathname, (await req.json().catch(() => ({}))) as Record<string, unknown>);
      if (!p) return json({ error: "bad request" }, 400);
      await env.DB.prepare("UPDATE inbox SET status=? WHERE id=?").bind(p.status, p.id).run();
      return json({ ok: true });
    }

    if (m === "POST" && pathname === "/car/send") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const p = parseSend((await req.json().catch(() => ({}))) as Record<string, unknown>);
      if ("error" in p) return json({ error: p.error }, 400);
      const r = await relaySend(env, p.to, p.body);
      await env.DB.prepare("INSERT INTO inbox(kind,status,message_id,from_label,summary,created_at,meta) VALUES('sent',?,?,?,?,?,?)")
        .bind(r.ok ? "sent" : "failed", r.message_id, p.label, p.body, now(), JSON.stringify({ http: r.http, code: r.code })).run();
      return json(r, r.ok ? 200 : 502);
    }

    // 데모 메일함(설계 O2): 실제 메일은 보내지 않고 inbox kind='sent'(channel=mail)에 저장
    if (m === "POST" && pathname === "/car/mail") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const b = (await req.json().catch(() => ({}))) as Record<string, unknown>;
      const to = typeof b.to === "string" ? b.to.trim() : "";
      const body = typeof b.body === "string" ? b.body.trim() : "";
      const subject = (typeof b.subject === "string" ? b.subject.trim() : "") || body.slice(0, 20);
      if (b.confirmed !== true) return json({ error: "confirmed must be true" }, 400);
      if (to.length < 1 || to.length > 100) return json({ error: "bad to" }, 400);
      if (body.length < 1 || body.length > 2000) return json({ error: "bad body" }, 400);
      if (subject.length > 100) return json({ error: "bad subject" }, 400);
      if (isBlocked(body, false) || isBlocked(subject, false)) return json({ error: "blocked body" }, 400);
      const r = await env.DB.prepare("INSERT INTO inbox(kind,status,from_label,summary,created_at,meta) VALUES('sent','sent',?,?,?,?)")
        .bind(to, body, now(), JSON.stringify({ channel: "mail", subject })).run();
      return json({ ok: true, id: r.meta.last_row_id });
    }

    // 집 문턱(설계 H1): 가족에게 'N시 N분 도착' 한 줄, 하루 한 번(KST)
    if (m === "POST" && pathname === "/car/family-eta") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const eta = ((await req.json().catch(() => ({}))) as Record<string, unknown>).eta_min;
      if (typeof eta !== "number" || !Number.isInteger(eta) || eta < 0 || eta > 120) return json({ error: "eta_min must be integer 0..120" }, 400);
      const st = (await kvGet(env.DB, "settings")) ?? {};
      if (!st.family_number) return json({ ok: true, skipped: "no_family" });
      const t = now();
      const day = new Date(t + 9 * 3_600_000).toISOString().slice(0, 10);
      if ((await kvGet(env.DB, "family_sent_day")) === day) return json({ ok: true, skipped: "sent_today" });
      const text = `${st.owner_label ?? "주인"}님 ${hhmm(t + eta * 60_000)}쯤 도착 예정이에요. (내비서)`;
      const r = await relaySend(env, st.family_number, text);
      await env.DB.prepare("INSERT INTO inbox(kind,status,message_id,from_label,summary,created_at,meta) VALUES('sent',?,?,'가족',?,?,?)")
        .bind(r.ok ? "sent" : "failed", r.message_id, text, t, JSON.stringify({ channel: "family", http: r.http, code: r.code })).run();
      if (r.ok) await env.DB.prepare("INSERT OR REPLACE INTO kv(key,value,updated_at) VALUES('family_sent_day',?,?)").bind(JSON.stringify(day), t).run();
      return json(r, r.ok ? 200 : 502);
    }

    // 폰 연결 앱(A안)이 받은 메시지 알림. 차와 같은 토큰
    if (m === "POST" && pathname === "/phone/notify") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const b = (await req.json().catch(() => ({}))) as Record<string, unknown>;
      const n = parseNotify((k) => b[k]);
      if (!n) return json({ error: "app·sender·text required" }, 400);
      const r = await saveNotif(env.DB, n);
      return json({ ok: true, id: r.meta.last_row_id });
    }

    // 차 음성 답장(되읽고 '보내' 확인된 것만) → 답장 대기열 kind='reply'
    if (m === "POST" && pathname === "/car/reply") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const p = parseReply((await req.json().catch(() => ({}))) as Record<string, unknown>);
      if ("error" in p) return json({ error: p.error }, 400);
      const n = await env.DB.prepare("SELECT from_label,meta FROM inbox WHERE id=? AND kind='notif'").bind(p.id).first<{ from_label: string; meta: string }>();
      if (!n) return json({ error: "notif not found" }, 404);
      const meta = JSON.parse(n.meta ?? "{}");
      if (!meta.replyable) return json({ error: "not replyable" }, 400);
      await env.DB.prepare("INSERT INTO inbox(kind,status,from_label,summary,created_at,meta) VALUES('reply','queued',?,?,?,?)")
        .bind(n.from_label, p.text, now(), JSON.stringify({ notif_id: p.id, app: meta.app, key: meta.key ?? "" })).run();
      return json({ ok: true });
    }

    // 폰 연결 앱이 보낼 답장을 가져간다(가져간 것은 delivered)
    if (m === "GET" && pathname === "/phone/replies") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const { results } = await env.DB.prepare(TAKE_REPLIES_SQL).all<{ id: number; from_label: string; summary: string; meta: string }>();
      return json({ items: results.map((r) => { const mt = JSON.parse(r.meta ?? "{}"); return { id: r.id, sender: r.from_label, text: r.summary, app: mt.app ?? "", key: mt.key ?? "" }; }) });
    }

    // H13: 첫 실행 안내용. 착신 받을 서비스 번호(환경값, 코드에 두지 않음)와 실제 착신 통화(ring)를 받은 적 있는지.
    if (m === "GET" && pathname === "/car/config") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const ring = await env.DB.prepare("SELECT 1 FROM inbox WHERE kind='ring' LIMIT 1").first();
      return json({ service_number: env.CLAWOPS_NUMBER ?? "", forwarded: !!ring });
    }

    if (m === "GET" && pathname === "/car/place-phone") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const q = new URL(req.url).searchParams;
      return json(await placePhone(env.KAKAO_REST_KEY, q.get("name") ?? "", q.get("lat") ?? "", q.get("lng") ?? ""));
    }

    if (m === "POST" && pathname === "/car/chat") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const b = (await req.json().catch(() => ({}))) as Record<string, unknown>;
      const text = typeof b.text === "string" ? b.text.trim().slice(0, 200) : "";
      if (!text) return json({ error: "text required" }, 400);
      const history = (Array.isArray(b.history) ? b.history : []).slice(-6)
        .filter((h): h is { role: "user" | "assistant"; text: string } => !!h && (h.role === "user" || h.role === "assistant") && typeof h.text === "string")
        .map((h) => ({ role: h.role, text: h.text.slice(0, 200) }));
      return json(await carChat(env.ANTHROPIC_API_KEY, text, history));
    }

    if (m === "GET" && pathname === "/car/schedule") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      return json({ items: (await kvGet(env.DB, "schedule")) ?? [] });
    }

    // F6 데모 모드 관리 페이지. 브라우저 기본 로그인 창(비밀번호 = ADMIN_TOKEN).
    if (pathname === "/admin" || pathname.startsWith("/admin/")) {
      if (!basicOk(req.headers.get("authorization"), env.ADMIN_TOKEN))
        return new Response("unauthorized", { status: 401, headers: { "www-authenticate": 'Basic realm="carbiseo-admin", charset="UTF-8"' } });
      // B안: 차가 만든 답장(최근 10건)을 '보낼 답장'으로 보여 준다
      const html = async (msg: string, status = 200) => {
        const { results } = await env.DB.prepare("SELECT from_label,summary,status FROM inbox WHERE kind='reply' ORDER BY id DESC LIMIT 10").all<ReplyRow>();
        const mails = await env.DB.prepare("SELECT from_label,summary,created_at,meta FROM inbox WHERE kind='sent' AND json_extract(meta,'$.channel')='mail' ORDER BY id DESC LIMIT 20").all<MailRow>();
        return new Response(adminPage(msg, results, mails.results), { status, headers: { "content-type": "text/html; charset=utf-8" } });
      };
      if (m === "GET" && pathname === "/admin") return html("");
      if (m === "POST" && pathname === "/admin/fake-call") {
        const c = parseFakeCall(await req.formData());
        if (!c) return html("입력 확인: 이름 1~10자(글자·숫자·공백), 용건 1~120자", 400);
        const cc = await kvGet(env.DB, "car_context");
        await env.DB.prepare("INSERT INTO inbox(kind,status,room,from_label,summary,urgent,created_at,raw,meta) VALUES('call','demo',?,?,?,?,?,?,?)")
          .bind(cc?.room ?? null, c.from_label, c.summary, c.urgent, now(), JSON.stringify(c), JSON.stringify({ demo: true, car: carChip(cc, now()) })).run();
        return html(`넣었습니다: ${c.from_label} / ${c.summary}`);
      }
      if (m === "POST" && pathname === "/admin/fake-notif") {
        const f = await req.formData();
        const n = parseNotify((k) => f.get(k));
        if (!n) return html("입력 확인: 앱·보낸이·내용을 채워 주세요", 400);
        await saveNotif(env.DB, n);
        return html(`알림을 넣었습니다: ${n.app} · ${n.sender}`);
      }
      if (m === "POST" && pathname === "/admin/reset-schedule") {
        const s = demoSchedule(now());
        await env.DB.prepare("INSERT OR REPLACE INTO kv(key,value,updated_at) VALUES('schedule',?,?)").bind(JSON.stringify(s), now()).run();
        return html(`일정 ${s.length}건으로 초기화했습니다`);
      }
      // 녹화용: 주인 번호로 건 전화도 일반 발신자처럼 AI 응대(기본 꺼짐)
      if (m === "POST" && pathname === "/admin/owner-as-guest") {
        const on = String((await req.formData()).get("on")) === "1";
        await env.DB.prepare("INSERT OR REPLACE INTO kv(key,value,updated_at) VALUES('owner_as_guest',?,?)").bind(JSON.stringify(on), now()).run();
        return html(on ? "주인 번호를 손님으로 취급합니다(켜짐)" : "주인 번호는 주인 모드입니다(꺼짐)");
      }
      // 녹화용 시연 시각: at=2026-11-02T10:30(KST) → 손님 대본의 평일·저녁·주말 판단만 그 시각으로. at=none 해제
      if (m === "POST" && pathname === "/admin/demo-clock") {
        const at = String((await req.formData()).get("at") ?? "none");
        const t = Date.parse(`${at}:00+09:00`);
        const off = Number.isNaN(t) ? 0 : t - now();
        await env.DB.prepare("INSERT OR REPLACE INTO kv(key,value,updated_at) VALUES('demo_offset',?,?)").bind(JSON.stringify(off), now()).run();
        return html(off ? `시연 시각 ${at} (대본만)` : "시연 시각 해제(실제 시각)");
      }
      // 녹화 전 부재중 카드 비우기. id는 AUTOINCREMENT라 차의 since 커서와 어긋나지 않는다.
      if (m === "POST" && pathname === "/admin/reset-inbox") {
        const r = await env.DB.prepare("DELETE FROM inbox").run();
        return html(`inbox ${r.meta.changes ?? 0}건을 비웠습니다(차 화면도 15초 안에 비워집니다)`);
      }
      // 재현 통화: 대본 1행(kind='replay', 차가 받는 즉시 재생·화면엔 안 보임) + 끝난 뒤 뜨는 '통화 정리' hint 카드 1행(created_at 미래 → 그때까지 차에 안 내려감)
      if (m === "POST" && pathname === "/admin/replay") {
        const t = now();
        const s = (await kvGet(env.DB, "schedule")) ?? demoSchedule(t);
        const cc = await kvGet(env.DB, "car_context");
        const r = replayScript(s, t, parseEta(cc?.eta_min));
        await env.DB.batch([
          env.DB.prepare("INSERT INTO inbox(kind,status,room,from_label,created_at,raw,meta) VALUES('replay','replay',?,?,?,?,?)")
            .bind(cc?.room ?? null, r.title, t, encodeReplay(r), "{}"),
          env.DB.prepare("INSERT INTO inbox(kind,status,room,from_label,summary,created_at,meta) VALUES('hint','replay',?,'통화 정리',?,?,?)")
            .bind(cc?.room ?? null, r.wrap, t + r.endMs + 6000, JSON.stringify({ replay: true })),
        ]);
        return html(`재현 통화를 시작했습니다(차가 5초 안에 받습니다, 약 ${Math.round(r.endMs / 1000)}초)`);
      }
    }

    return json({ error: "not found" }, 404);
  },
  // 보관 정책: 웹훅 원문(raw)은 7일 뒤 비우고, 행 전체는 30일 뒤 삭제 (wrangler.toml crons, 매일 1회)
  async scheduled(_e: unknown, env: Env) {
    for (const [sql, cut] of retention(now())) await env.DB.prepare(sql).bind(cut).run();
  },
};
