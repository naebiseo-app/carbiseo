import { CALL_ID, carChip, callerNumber, cleanMent, cleanName, field, freshCtx, hhmm, isUrgent, nextEventAt, ownerScript, parseCarCtx, parseEta, parseMode, parseRoom, pickScript, isDirect, parkingScript, reserveScript, sameNumber, type Mode, type Recent } from "./script.ts";
import { adminPage, basicOk, demoSchedule, encodeReplay, parseFakeCall, replayScript, type MailRow, type ReplyRow } from "./admin.ts";
import { FALLBACK, guardText, isBlocked } from "./guard.ts";
import { connectGoogle, disconnectGoogle, googleStatus, syncCalendar, syncIfStale } from "./calendar.ts";
import { parkingNear, type Lot } from "./parking.ts";
import parkingLots from "./parking_lots.json" with { type: "json" };
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
  PARKING_API_KEY?: string; // 공공 실시간 주차(/car/parking). 없으면 no_key
  GOOGLE_CLIENT_SECRET?: string; // 구글 캘린더 연결(/phone/google). 없으면 no_secret
  O4_LIVE?: string; // "1"일 때만 예약 전화가 실제 가게 번호로 간다. 아니면 항상 OWNER_NUMBER
}

const json = (o: unknown, status = 200) =>
  new Response(JSON.stringify(o), { status, headers: { "content-type": "application/json; charset=utf-8" } });

const now = () => Date.now();

const RESERVE_DAILY_MAX = 5; // 예약 전화 하루(KST) 상한: 오발신·반복 호출로 전화비가 새는 것을 막는다

export async function kvGet(db: D1Database, key: string) {
  const r = await db.prepare("SELECT value FROM kv WHERE key=?").bind(key).first<{ value: string }>();
  return r ? JSON.parse(r.value) : null;
}

export async function kvPut(db: D1Database, key: string, value: unknown) {
  await db.prepare("INSERT INTO kv(key,value,updated_at) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at").bind(key, JSON.stringify(value), now()).run();
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

// 결과 = ok | fail | nokey | nosig. ok가 아니면 /clawops/events가 401로 거절한다.
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
// 같은 통화의 카드는 1장만: 웹훅 재전송, 받아쓰기 카드 뒤 오는 summary.completed는 건너뛴다
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

const SUMMARY_SYSTEM = "전화 받아쓰기를 운전자가 볼 메모로 정리합니다. 받아쓰기에 있는 내용만 쓰고, 상대가 말하지 않은 것은 없다고도 쓰지 않습니다. 연락 시각을 말했으면 summary에 반드시 넣습니다. '아, 네' 같은 맞장구는 이름이 아니고, 비서가 되읽어 상대가 고친 이름이 있으면 고친 이름을 씁니다. 전화 받아쓰기는 상호·이름을 비슷한 소리의 다른 말로 자주 틀립니다. 맨 끝 [참고 이름 목록]에 발음이 비슷한 이름이 있으면 그 이름으로 고쳐 쓰고(예: 받아쓰기 '한비 물산' → 목록 '한빛물산'), 비슷한 것이 없으면 받아쓰기 그대로 씁니다. 같은 식으로 문맥상 어색한 용건 낱말은 발음이 비슷한 흔한 업무 낱말로 고칩니다(예: '공적 사건' → '견적 건'). JSON 한 줄만 답합니다: {\"caller\":\"상대 이름·소속, 없으면 빈 문자열\",\"summary\":\"존댓말 한두 문장. 예: 김민수 님이 미팅 변경 건으로 전화하셨고 3시까지 연락을 부탁하셨어요. 자료도 보내 달라고 하셨어요.\",\"callback_by\":\"상대가 말한 연락 시각 표현 그대로(예: 3시까지), 없으면 빈 문자열\"}";

const RESERVE_SYSTEM = "AI 비서가 식당·가게에 건 예약 문의 전화 받아쓰기를 운전자가 볼 결과로 정리합니다. 받아쓰기에 있는 내용만 쓰고 없는 내용은 지어내지 않습니다. status는 가게가 예약을 확정했다고 분명히 말했을 때만 ok이고(예약 질문에 '그러세요'·'오세요'로 답한 것도 확정입니다), 안 된다·모르겠다·나중에·직접 연락 같은 말이거나 확정이 불분명하면 need_manual입니다. parking은 가게가 주차 가능이라고 하면 가능, 안 된다고 하면 불가, 말이 없거나 불분명하면 모름입니다. summary는 존댓말 한 문장이고 전화번호는 쓰지 않습니다. JSON 한 줄만 답합니다: {\"status\":\"ok|need_manual\",\"summary\":\"예: 18:30 4명 예약이 확정됐어요. 주차는 가능하대요.\",\"parking\":\"가능|불가|모름\"}";

const PARKING_SYSTEM = "AI 비서가 가게·주차장에 건 주차 문의 전화 받아쓰기를 운전자가 볼 결과로 정리합니다. 받아쓰기에 있는 내용만 쓰고 없는 내용은 지어내지 않습니다. status는 지금 주차 자리가 있다고 분명히 답했을 때만 ok이고, 자리가 없다거나 말이 불분명하면 need_manual입니다. parking은 주차 가능이라고 하면 가능, 안 된다고 하면 불가, 말이 없거나 불분명하면 모름입니다. summary는 존댓말 한 문장이고 전화번호는 쓰지 않습니다. JSON 한 줄만 답합니다: {\"status\":\"ok|need_manual\",\"summary\":\"예: 지금 주차 자리가 있고 한 시간에 삼천 원이래요.\",\"parking\":\"가능|불가|모름\"}";

const TASK_SYSTEM = `주인이 비서에게 건 전화 받아쓰기에서 주인이 시킨 일만 뽑습니다. 받아쓰기에 있는 내용만 씁니다. JSON 한 줄만 답합니다: {"tasks":["일 한 건","일 한 건"],"sends":[{"to":"이지은","body":"3시에 도착합니다."}]}. sends에는 주인이 문자를 보내라고 했고, 비서가 받는 사람과 내용을 되읽었고, 그 뒤 주인이 취소하거나 고치지 않은 문자만 넣습니다(고쳤으면 고친 내용으로). sends에 넣은 문자는 tasks에 넣지 않습니다. 비서가 되읽기 전에 끊겼으면 sends에 넣지 않고 tasks에 '이지은에게 문자 / 내용: 3시에 도착합니다.'처럼 넣습니다. 메일과 그 밖의 일은 tasks에 넣습니다. 메일은 '메일: 받는사람 / 제목 / 내용', 그 밖의 일은 '내일 오전 박도윤 님께 견적서 보내기'처럼 짧게 씁니다. 일정이나 전화 내용을 묻기만 한 통화는 {"tasks":[]}로 답합니다.`;

// 받아쓰기 → 메모. 실패는 null(호출한 쪽이 상대 말 그대로 쓴다)
async function summarizeCall(key: string | undefined, system: string, dialog: string): Promise<{ caller?: string; summary?: string; callback_by?: string; tasks?: string[]; sends?: { to?: string; body?: string }[]; status?: string; parking?: string } | null> {
  if (!key) return null;
  try {
    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({ model: "claude-haiku-5-5", max_tokens: 600, thinking: { type: "disabled" }, system, messages: [{ role: "user", content: dialog }] }),
      signal: AbortSignal.timeout(10000),
    });
    if (!r.ok) return null;
    const d = (await r.json()) as { content?: { type?: string; text?: string }[] };
    return JSON.parse(d.content?.find((b) => b.type === "text")?.text?.match(/\{[\s\S]*\}/)?.[0] ?? "null");
  } catch { return null; }
}

// 이름 → 문자 번호. 이름이 정확히 같을 때만(비슷한 이름 추측 금지). 연락처 → 최근 전화 카드 → 일정 순. 가짜 시연 번호(0100000…)는 없는 것으로 친다
const realNum = (n: unknown) => { const d = String(n ?? "").replace(/\D/g, ""); return /^\d{10,11}$/.test(d) && !/^0100000/.test(d) ? d : null; };
export async function numberFor(env: Pick<Env, "DB">, raw: string): Promise<string | null> {
  const name = raw.trim().replace(/\s*(님|씨)$/, "").trim();
  if (!name) return null;
  const c = await kvGet(env.DB, "contacts");
  const a = c && typeof c === "object" && Object.hasOwn(c, name) ? realNum(c[name]) : null;
  if (a) return a;
  const row = await env.DB.prepare("SELECT from_number FROM inbox WHERE kind='call' AND from_label=? AND from_number IS NOT NULL ORDER BY id DESC LIMIT 1").bind(name).first<{ from_number: string }>();
  const b = realNum(row?.from_number);
  if (b) return b;
  const sc = await kvGet(env.DB, "schedule");
  for (const e of Array.isArray(sc) ? sc : []) {
    const ct = typeof e?.contact === "string" ? e.contact : "";
    if (ct && (ct === name || ct.split(" ")[0] === name) && realNum(e.contact_number)) return realNum(e.contact_number);
  }
  return null;
}

// 문자 발송 + inbox 'sent' 행. /car/send(call_id 없음)와 통화 뒤 자동 발송이 같이 쓴다
async function sendText(env: Env, to: string, body: string, label: string, callId: string | null) {
  const r = await relaySend(env, to, body);
  await env.DB.prepare("INSERT INTO inbox(kind,status,message_id,call_id,from_label,summary,created_at,meta) VALUES('sent',?,?,?,?,?,?,?)")
    .bind(r.ok ? "sent" : "failed", r.message_id, callId, label, body, now(), JSON.stringify({ http: r.http, code: r.code })).run();
  return r;
}

// 받아쓰기 조회 → 말한 것(상대 쪽)·번호 가린 대화. 첫 화자 = AI 소개. aiSays가 있으면 그 글이 들어 있는 첫 화자를 AI로(걸어 보낸 전화는 가게가 먼저 말할 수 있다)
async function loadDialog(env: Env, id: string, aiSays?: string) {
  const r = await fetch(`https://api.claw-ops.com/v1/accounts/${env.CLAWOPS_ACCOUNT_ID}/calls/${id}/transcript`, { headers: { Authorization: `Bearer ${env.CLAWOPS_API_KEY}` } });
  const segs = ((await r.json()) as { segments?: { speaker: string; text: string }[] }).segments ?? [];
  const ai = (aiSays && segs.find((s) => s.text.includes(aiSays))?.speaker) || segs[0]?.speaker;
  const said = segs.filter((s) => s.speaker !== ai).map((s) => s.text).join(" ");
  const dialog = segs.map((s) => `${s.speaker === ai ? "비서" : "상대"}: ${s.text}`).join("\n").replace(/\d[\d -]{7,}\d/g, "[번호]"); // 전화번호는 외부 AI로 보내지 않는다
  return { said, dialog };
}

// 예약 전화 결과: 받아쓰기 → RESERVE_SYSTEM 요약 → 같은 행 갱신. 'calling'일 때만 갱신하므로 웹훅이 두 번 와도 한 번만 바뀐다. 실패·대화 없음은 직접 확인
async function reserveResult(env: Env, id: string) {
  let memo: Awaited<ReturnType<typeof summarizeCall>> = null;
  try {
    const { said, dialog } = await loadDialog(env, id, "AI 비서");
    const pk = (await env.DB.prepare("SELECT json_extract(meta,'$.purpose') AS p FROM inbox WHERE kind='reserve' AND call_id=?").bind(id).first<{ p: string | null }>())?.p === "parking";
    memo = said ? await summarizeCall(env.ANTHROPIC_API_KEY, pk ? PARKING_SYSTEM : RESERVE_SYSTEM, dialog) : null;
  } catch (e) { console.log("reserve transcript error", String(e)); }
  const parking = ["가능", "불가", "모름"].includes(memo?.parking ?? "") ? memo!.parking! : "모름";
  const summary = memo?.summary ? guardText("summary", String(memo.summary)) : "직접 확인이 필요해요";
  await env.DB.prepare("UPDATE inbox SET status=?, summary=?, meta=json_set(COALESCE(meta,'{}'),'$.parking',?) WHERE kind='reserve' AND call_id=? AND status='calling'")
    .bind(memo?.status === "ok" ? "ok" : "need_manual", summary, parking, id).run();
  return !!memo;
}

// 손님 통화 카드는 끊은 뒤 받아쓰기로 만든다. 통화 중 도구 호출은 답을 7초 늦추고 말을 두 번 하게 했다(11-06 실통화), summary.completed는 안 온다(12통 전부 not_requested)
// 요약이 저장된 통화(수신·예약 전화)는 녹음을 지운다. keep_recordings(촬영 기간)가 켜져 있으면 남긴다. 실패해도 요약은 이미 저장됨
async function transcriptCard(env: Env, raw: string) {
  const id = new URLSearchParams(raw).get("CallId") ?? "";
  if (!(await transcriptWork(env, raw)) || (await kvGet(env.DB, "keep_recordings"))) return;
  try {
    const r = await fetch(`https://api.claw-ops.com/v1/accounts/${env.CLAWOPS_ACCOUNT_ID}/recordings/${id}`, { method: "DELETE", headers: { Authorization: `Bearer ${env.CLAWOPS_API_KEY}` } });
    console.log("recording delete", id, r.status);
  } catch (e) { console.log("recording delete error", String(e)); }
}

// 전화 받아쓰기는 낯선 상호를 자주 틀린다(11-10 '한빛물산'류 상호 → '세금 상사'). 주인 연락처·일정에 있는 이름을 요약 단계에 참고로 준다.
// 지난 카드의 from_label은 넣지 않는다(틀린 이름이 다시 굳는다).
export function namesFrom(contacts: unknown, schedule: unknown): string[] {
  const c = contacts && typeof contacts === "object" ? Object.keys(contacts) : [];
  const ev = Array.isArray(schedule) ? schedule.flatMap((e: any) => [e?.title, e?.place]) : [];
  return [...new Set([...c, ...ev].filter((x): x is string => typeof x === "string" && x.trim() !== "").map((x) => x.trim().slice(0, 30)))].slice(0, 40);
}
async function hintNames(env: Env) {
  return namesFrom(await kvGet(env.DB, "contacts"), await kvGet(env.DB, "schedule"));
}

// 요약을 저장했으면 true(이미 처리된 재전송·무관한 이벤트·요약 실패는 false)
async function transcriptWork(env: Env, raw: string): Promise<boolean> {
  const f = new URLSearchParams(raw), id = f.get("CallId") ?? "", from = f.get("From") ?? "";
  if (f.get("Event") !== "transcript.completed" || !env.CLAWOPS_API_KEY) return false;
  if (f.get("Direction") === "outbound" && (await env.DB.prepare("SELECT 1 FROM inbox WHERE kind='reserve' AND call_id=? AND status='calling'").bind(id).first())) {
    return reserveResult(env, id);
  }
  if (f.get("Direction") !== "inbound") return false;
  const owner = sameNumber(from, env.OWNER_NUMBER) && !(await kvGet(env.DB, "owner_as_guest"));
  const had = owner ? "SELECT 1 FROM inbox WHERE kind IN ('task','sent') AND call_id=?" : "SELECT 1 FROM inbox WHERE kind='call' AND call_id=? AND status<>'ringing'";
  if (await env.DB.prepare(had).bind(id).first()) return false;
  const { said, dialog } = await loadDialog(env, id);
  if (owner) { // 주인 통화 → 되읽고 확인한 문자는 바로 발송, 시킨 일마다 할 일 카드. 요약 실패면 주인 말 그대로 1장(자동 발송 없음)
    if (!said) return false;
    const memo = await summarizeCall(env.ANTHROPIC_API_KEY, TASK_SYSTEM, dialog);
    const cc = await kvGet(env.DB, "car_context");
    const sends = Array.isArray(memo?.sends) ? memo.sends : [];
    const tasks = (Array.isArray(memo?.tasks) ? memo.tasks : sends.length ? [] : [said]).map(String);
    for (const x of sends) {
      const to = String(x?.to ?? "").trim().slice(0, 40), body = String(x?.body ?? "").trim();
      if (!to || !body) continue;
      const num = await numberFor(env, to);
      const p = num && !body.includes("[번호]") ? parseSend({ to_number: num, body, to_label: to, confirmed: true }) : null;
      const r = p && !("error" in p) ? await sendText(env, p.to, p.body, p.label, id) : null;
      if (!r?.ok) tasks.push(`${to}에게 문자 / 내용: ${body}`); // 못 보낸 문자는 할 일로 남긴다
    }
    for (const t of tasks)
      await env.DB.prepare("INSERT INTO inbox(kind,status,room,call_id,from_label,summary,created_at) VALUES('task','open',?,?,'주인',?,?)")
        .bind(parseRoom(cc?.room) ?? null, id, guardText("summary", String(t)), now()).run();
    return true;
  }
  const names = await hintNames(env);
  const memo = said ? await summarizeCall(env.ANTHROPIC_API_KEY, SUMMARY_SYSTEM, names.length ? `${dialog}

[참고 이름 목록] ${names.join(", ")}` : dialog) : null;
  const summary = memo?.summary || (said ? `상대 말: ${said}` : "말씀 없이 끊긴 전화예요");
  const cols = fromCols(from), who = String(memo?.caller ?? "").replace(/["'`{}]/g, "").slice(0, 40);
  await saveCallCard(env.DB, { call_id: id, ...cols, from_label: who || cols.from_label, summary, urgent: isUrgent(`${summary} ${said}`) ? 1 : 0 },
    { via: "transcript", caller: who, callback_by: String(memo?.callback_by ?? "").slice(0, 50) }, from);
  return !said || !!memo; // 요약 실패면 녹음을 남긴다
}

// 최소 MCP streamable HTTP(JSON-RPC 2.0, 단일 JSON 응답). 배치·SSE·세션은 안 씀
async function mcp(req: Request): Promise<Response> {
  const msg = (await req.json().catch(() => null)) as { id?: unknown; method?: unknown; params?: any } | null;
  if (!msg || typeof msg.method !== "string") return json({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "parse error" } }, 400);
  if (msg.id === undefined) return new Response(null, { status: 202 }); // 알림(notifications/initialized 등)
  const ok = (result: unknown) => json({ jsonrpc: "2.0", id: msg.id, result });
  const p = msg.params ?? {};
  if (msg.method === "initialize") return ok({ protocolVersion: p.protocolVersion ?? "2025-06-18", capabilities: { tools: {} }, serverInfo: { name: "carbiseo", version: "1.0.0" } });
  if (msg.method === "ping") return ok({});
  if (msg.method === "tools/list") return ok({ tools: [] }); // 카드는 통화 뒤 받아쓰기로. ClawOps 연결은 PATCH가 막혀 있어 도구 목록을 비운다
  if (msg.method === "tools/call") return ok({ content: [{ type: "text", text: "unknown tool" }], isError: true });
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
  // 데모 일정 연락처(010-0000-000x, 개통되지 않는 번호대)는 실제로 보내지 않고 보낸 것으로만 기록한다(시연 촬영용)
  if (/^0100000000\d$/.test(to.replace(/\D/g, ""))) return { ok: true, http: 200, code: "demo", message_id: null };
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
const statusField = (raw: string, ...ks: string[]) => {
  let o: Record<string, unknown> = {};
  try { o = JSON.parse(raw); } catch { o = Object.fromEntries(new URLSearchParams(raw)); }
  const hit = Object.entries(o ?? {}).find(([k]) => ks.includes(k.toLowerCase()));
  return hit ? String(hit[1]) : "";
};
export function statusDone(raw: string): string | null {
  const st = statusField(raw, "callstatus", "status", "event", "statuscallbackevent").toLowerCase();
  const id = statusField(raw, "callid", "callsid", "call_id");
  return st.includes("completed") && /^CA[0-9a-f]{20,40}$/i.test(id) ? id : null;
}

// 걸어 보낸 전화가 연결 안 된 채 끝났을 때(무응답·통화중·실패·취소)의 CallId. ponytail: 실제 필드·값 이름 미확인이라 CallStatus·Status 두 키에서 Twilio식 값으로 찾는다. 실통화 웹훅 본문을 보고 맞추기
export function statusFailed(raw: string): string | null {
  const st = statusField(raw, "callstatus", "status").toLowerCase();
  return /busy|no.?answer|fail|cancel/.test(st) ? statusField(raw, "callid", "callsid", "call_id") || null : null;
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
  "SELECT * FROM inbox WHERE id>?1 AND (?1>0 OR IFNULL(kind,'')<>'event') AND id < COALESCE((SELECT MIN(id) FROM inbox WHERE id>?1 AND created_at>?2), 9e18) ORDER BY CASE WHEN ?1=0 THEN -id ELSE id END LIMIT 51";

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

type ParkTaskRow = { id: number; title: string; sub: string };
/** 하차 카드 할 일: {id,title,sub?} 객체만, 최대 8개, 제목 80자·보조 줄 60자. 잘못된 항목은 버린다. */
export function parkTasks(v: unknown): ParkTaskRow[] {
  return (Array.isArray(v) ? v : []).flatMap((t): ParkTaskRow[] => {
    const id = (t as ParkTaskRow)?.id, title = (t as ParkTaskRow)?.title, sub = (t as ParkTaskRow)?.sub;
    return typeof id === "number" && Number.isInteger(id) && typeof title === "string" && title.trim() !== "" ? [{ id, title: title.slice(0, 80), sub: typeof sub === "string" ? sub.slice(0, 60) : "" }] : [];
  }).slice(0, 8);
}

/** 좌표 -> 도로명 또는 지번 주소(카카오 coord2address). 키 없음·실패는 빈 문자열. 좌표는 로그에 남기지 않는다. */
export async function coordAddress(key: string | undefined, lat: number, lng: number): Promise<string> {
  if (!key) return "";
  try {
    const r = await fetch(`https://dapi.kakao.com/v2/local/geo/coord2address.json?x=${lng}&y=${lat}`, { headers: { Authorization: `KakaoAK ${key}` } });
    if (!r.ok) return "";
    const d = ((await r.json()) as { documents?: { road_address?: { address_name?: string } | null; address?: { address_name?: string } | null }[] }).documents?.[0];
    return d?.road_address?.address_name || d?.address?.address_name || "";
  } catch { return ""; }
}

/** 차 현재 좌표 근처 가까운 순 최대 15곳(카카오 카테고리 검색, 반경 5km). 지나온 곳을 거르는 건 차가 한다. category는 FD6 식당·CE7 카페·OL7 주유소·CS2 충전소만. 좌표·결과는 저장·로그하지 않는다. */
export async function nearby(key: string | undefined, category: string, lat: string, lng: string): Promise<{ places: { name: string; lat: number; lng: number; distance: number }[] } | { reason: string }> {
  if (!key) return { reason: "no_key" };
  if (!["FD6", "CE7", "OL7", "CS2"].includes(category) || lat.trim() === "" || lng.trim() === "" || !Number.isFinite(Number(lat)) || !Number.isFinite(Number(lng))) return { reason: "bad_request" };
  try {
    const r = await fetch(`https://dapi.kakao.com/v2/local/search/category.json?category_group_code=${category}&x=${lng}&y=${lat}&radius=5000&sort=distance&size=15`, { headers: { Authorization: `KakaoAK ${key}` } });
    if (!r.ok) return { reason: "upstream" };
    const docs = ((await r.json()) as { documents?: { place_name?: string; y?: string; x?: string; distance?: string }[] }).documents ?? [];
    const places = docs.slice(0, 15).map((d) => ({ name: d.place_name ?? "", lat: Number(d.y), lng: Number(d.x), distance: Number(d.distance) }))
      .filter((p) => p.name && Number.isFinite(p.lat) && Number.isFinite(p.lng) && Number.isFinite(p.distance));
    return places.length ? { places } : { reason: "not_found" };
  } catch { return { reason: "upstream" }; }
}

const CHAT_SYSTEM = "당신은 운전자의 말동무 '내비서'입니다. 운전 중이므로 존댓말로 두 문장 이내, 60자 안으로 짧게 답합니다. 사용자가 '졸려', '피곤해'처럼 말하면 물음표가 붙어도 운전자 본인 이야기입니다. 당신이 졸린지 묻는 말로 받지 말고, 가까운 휴게소에서 쉬어 가시라고 권합니다. 편안한 잡담을 이어 가고 가끔 짧은 질문을 던져 운전자가 깨어 있게 돕습니다. 화면을 보라는 말, 목록, 이모지, 기호는 쓰지 않습니다. 의학·법률·투자 조언은 하지 않습니다. 추월·속도·차선 같은 운전 조작은 권하지 않습니다. 식당 찾기, 날씨 확인처럼 할 수 없는 일을 해 주겠다고 말하지 않습니다. 졸리다, 피곤하다는 말에는 가까운 휴게소에서 쉬기를 권합니다.";

const DROWSY = /졸려|졸리|졸음|피곤|잠와|잠온/;
const DROWSY_REPLY = "졸리시면 위험해요. 가까운 휴게소나 졸음쉼터에서 잠깐 쉬어 가세요.";

/** 정차 중(PARK)엔 "운전 중" 전제를 빼고 그 말을 하지 않게 한다(회귀 11-09: P에서 "운전 중이시니" 답). */
export const chatSystemParked = () => CHAT_SYSTEM.replace("운전 중이므로", "지금은 차를 세워 둔 상태입니다. 운전 중이라는 말은 하지 말고") ;

/** 말동무 답이 길면 문장 단위로 110자 안까지만 남긴다(가드 120자에 걸려 고정 문구로 바뀌지 않게). */
export function chatCut(t: string): string {
  let out = "";
  for (const s of t.match(/[^.?!]+[.?!]*\s*/g) ?? []) { if ((out + s).length > 110) break; out += s; }
  return out.trim() || t.slice(0, 110);
}

/** 말동무 대화. 대화 내용은 저장·로그하지 않는다. 키 없음 no_key, 실패·금칙어는 고정 문구 fallback. */
export async function carChat(key: string | undefined, text: string, history: { role: "user" | "assistant"; text: string }[], parked = false): Promise<{ reply: string; source: "llm" | "fallback" | "no_key" }> {
  if (DROWSY.test(text.replace(/\s/g, ""))) return { reply: DROWSY_REPLY, source: "fallback" }; // 안전 문구는 AI 답에 맡기지 않는다("저는 졸리지 않아요" 실측)
  if (!key) return { reply: FALLBACK.chat, source: "no_key" };
  const fb = { reply: FALLBACK.chat, source: "fallback" as const };
  try {
    const messages = [...history, { role: "user" as const, text }].map((h) => ({ role: h.role, content: h.text }));
    while (messages[0].role === "assistant") messages.shift(); // API는 user로 시작해야 한다
    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": key, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({ model: "claude-haiku-5-5", max_tokens: 200, thinking: { type: "disabled" }, system: parked ? chatSystemParked() : CHAT_SYSTEM, messages }),
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

    // ClawOps 에이전트 MCP 연결(도구 없음, 응답만). 비밀은 경로에(authType none)
    if (pathname.startsWith("/mcp/")) {
      if (pathname.slice("/mcp/".length) !== env.CALL_CONTEXT_SECRET) return json({ error: "not found" }, 404);
      if (m !== "POST") return new Response(null, { status: 405, headers: { allow: "POST" } });
      return mcp(req);
    }

    // 번호 statusCallback: 통화가 끝나는 즉시 받아쓰기 요청 → 약 16초 뒤 summary.completed(카드). managed_agent 모드는 agent.disconnected 웹훅이 안 와서(10-31 실측) 이 경로가 주 경로
    if (m === "POST" && pathname.startsWith("/clawops/status/")) {
      if (pathname.slice("/clawops/status/".length) !== env.CALL_CONTEXT_SECRET) return json({ error: "not found" }, 404);
      const raw = await req.text();
      const id = statusDone(raw);
      await saveInbox(env.DB, "event", "status", null, raw, { ct: req.headers.get("content-type") });
      const failed = statusFailed(raw);
      if (failed) await env.DB.prepare("UPDATE inbox SET status='need_manual', summary='전화를 받지 않았어요' WHERE kind='reserve' AND call_id=? AND status='calling'").bind(failed).run();
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
      if (sig !== "ok") return json({ error: "bad_signature" }, 401); // 11-06 실통화 이벤트가 ok로 카드까지 간 것 확인 뒤 거절로 전환
      await saveInbox(env.DB, "event", "received", null, raw, { ct: req.headers.get("content-type"), sig, sig_header: req.headers.get("x-signature") });
      // 계정 '통화 받아쓰기'가 꺼져 있어 통화가 끝나면 받아쓰기를 직접 요청한다 → transcript.completed → summary.completed(아래 카드)
      const callId = sig === "ok" ? transcriptTarget(raw) : null;
      if (callId && env.CLAWOPS_API_KEY) {
        const r = await fetch(`https://api.claw-ops.com/v1/accounts/${env.CLAWOPS_ACCOUNT_ID}/calls/${callId}/transcript`,
          { method: "POST", headers: { Authorization: `Bearer ${env.CLAWOPS_API_KEY}` } });
        console.log("transcript request", callId, r.status); // 202 접수, 409 이미 요청됨
      }
      if (sig === "ok") { // 받아쓰기·요약·발송은 웹훅 응답을 늦추지 않게 뒤에서
        const job = transcriptCard(env, raw).catch((e) => console.log("transcript card error", String(e)));
        if (ctx) ctx.waitUntil(job); else await job;
      }
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
      const r = await sendText(env, p.to, p.body, p.label, null);
      return json(r, r.ok ? 200 : 502);
    }

    // AI 예약 전화: 가게에 '몇 시 몇 명 예약·주차' 전달만. 결과는 통화 뒤 받아쓰기로 같은 행에(reserveResult)
    if (m === "POST" && pathname === "/car/reserve") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const b = (await req.json().catch(() => ({}))) as Record<string, unknown>;
      const place = typeof b.place === "string" ? b.place.trim() : "";
      const arrive = typeof b.arrive === "string" && /^([01]\d|2[0-3]):[0-5]\d$/.test(b.arrive) ? b.arrive : "";
      const pk = b.purpose === "parking"; // 주차 문의: 시간·인원 없이. 하루 상한(아래 reserve 행 수)·O4_LIVE 규칙은 예약과 같다
      const party = b.party;
      const geo = (v: unknown) => (v === undefined ? "" : typeof v === "number" || typeof v === "string" ? String(v) : null);
      const lat = geo(b.lat), lng = geo(b.lng);
      if (place.length < 1 || place.length > 40) return json({ error: "bad place" }, 400);
      if (!pk && !arrive) return json({ error: "bad arrive" }, 400);
      if (!pk && (typeof party !== "number" || !Number.isInteger(party) || party < 1 || party > 20)) return json({ error: "bad party" }, 400);
      if (lat === null || lng === null || (lat !== "" && !Number.isFinite(Number(lat))) || (lng !== "" && !Number.isFinite(Number(lng)))) return json({ error: "bad lat/lng" }, 400);
      const day0 = Math.floor((now() + 9 * 3_600_000) / 86_400_000) * 86_400_000 - 9 * 3_600_000; // 오늘 0시(KST)
      const used = await env.DB.prepare("SELECT COUNT(*) AS n FROM inbox WHERE kind='reserve' AND created_at>=?").bind(day0).first<{ n: number }>();
      if ((used?.n ?? 0) >= RESERVE_DAILY_MAX) return json({ ok: false, code: "daily_limit" });
      // ponytail: 시연·심사 중 실제 가게로 전화가 가면 안 된다. O4_LIVE=1일 때만 실제 가게 번호, 그 외엔 항상 주인 번호로 건다
      const to = (env.O4_LIVE === "1" ? (await placePhone(env.KAKAO_REST_KEY, place, lat, lng)).phone : env.OWNER_NUMBER)?.replace(/[\s-]/g, "");
      if (!to) return json({ ok: false, code: "no_number" });
      const owner = (await kvGet(env.DB, "settings"))?.owner_label;
      let callId = "";
      try {
        const r = await fetch(`https://api.claw-ops.com/v1/accounts/${env.CLAWOPS_ACCOUNT_ID}/calls`, {
          method: "POST",
          headers: { Authorization: `Bearer ${env.CLAWOPS_API_KEY}`, "Content-Type": "application/json" },
          body: JSON.stringify({ From: env.CLAWOPS_NUMBER, To: to, AgentId: "cmw4rd0fb002i01s6grhclc21", CallContext: { Instruction: pk ? parkingScript({ owner, place }) : reserveScript({ owner, phone: env.OWNER_NUMBER, place, arrive, party: party as number }), Variables: {} }, StatusCallback: `${new URL(req.url).origin}/clawops/status/${env.CALL_CONTEXT_SECRET}`, Timeout: 30 }),
        });
        const j = (await r.json().catch(() => ({}))) as { callId?: string; CallId?: string };
        if (r.ok) callId = String(j.callId ?? j.CallId ?? "");
      } catch (e) { console.log("reserve call error", String(e)); }
      if (!callId) return json({ ok: false, code: "call_failed" });
      const r = await env.DB.prepare("INSERT INTO inbox(kind,status,call_id,from_label,summary,created_at,meta) VALUES('reserve','calling',?,?,?,?,?)")
        .bind(callId, place, pk ? "주차 문의 전화 중" : `${arrive} ${party}명 예약 전화 중`, now(), JSON.stringify(pk ? { purpose: "parking" } : { party, arrive })).run();
      return json({ ok: true, id: r.meta.last_row_id });
    }

    // 하차 정보: 마지막 1건만(kv 'parked'). 좌표·주소는 로그에 남기지 않는다
    if (m === "POST" && pathname === "/car/parked") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const b = (await req.json().catch(() => ({}))) as Record<string, unknown>;
      if (b.clear === true) { await env.DB.prepare("DELETE FROM kv WHERE key='parked'").run(); return json({ ok: true }); }
      const num = (v: unknown) => (typeof v === "number" || (typeof v === "string" && v.trim() !== "") ? Number(v) : NaN);
      const lat = num(b.lat), lng = num(b.lng);
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) return json({ ok: false }, 400);
      const memo = typeof b.memo === "string" ? b.memo.slice(0, 40) : "";
      const tasks = parkTasks(b.tasks);
      const total = typeof b.total === "number" && Number.isInteger(b.total) ? Math.min(Math.max(b.total, tasks.length), 999) : tasks.length; // 8건으로 자르기 전 전체 건수
      const address = await coordAddress(env.KAKAO_REST_KEY, lat, lng);
      const done = ((await kvGet(env.DB, "parked")) as { done?: number[] } | null)?.done ?? []; // 같은 주차 중 재전송에도 폰에서 끝낸 일을 잃지 않는다
      await kvPut(env.DB, "parked", { lat, lng, address, memo, tasks, total, at: now(), done });
      return json({ ok: true, address: address || null });
    }

    // 차가 폰에서 끝낸 일 id를 가져간다
    if (m === "GET" && pathname === "/car/parked") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      return json({ done: ((await kvGet(env.DB, "parked")) as { done?: number[] } | null)?.done ?? [] });
    }

    // 폰이 하차 정보를 가져간다(/phone/replies와 같은 인증). 번호는 inbox에서 그때그때 찾고 kv에는 남기지 않는다
    if (m === "GET" && pathname === "/phone/parked") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const p = (await kvGet(env.DB, "parked")) as { tasks?: unknown; total?: number; done?: number[] } | null;
      if (!p) return json({ parked: null });
      const tasks = [], all = parkTasks(p.tasks);
      for (const t of all) {
        if (p.done?.includes(t.id)) continue;
        const r = t.id > 0 ? await env.DB.prepare("SELECT from_number FROM inbox WHERE id=?").bind(t.id).first<{ from_number: string | null }>() : null;
        tasks.push({ ...t, number: r?.from_number || null });
      }
      const { done: _, ...rest } = p;
      const total = Math.max(tasks.length, (typeof p.total === "number" ? p.total : all.length) - (all.length - tasks.length)); // 폰에서 끝낸 건수만큼 뺀다
      return json({ parked: { ...rest, tasks, total } });
    }

    // 폰에서 할 일을 끝냄: kv done에 추가하고, 서버 행이면 차 [완료] 버튼과 같은 처리(status=done)
    if (m === "POST" && pathname === "/phone/parked/done") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const id = ((await req.json().catch(() => ({}))) as Record<string, unknown>).id;
      if (typeof id !== "number" || !Number.isInteger(id)) return json({ error: "bad request" }, 400);
      const p = (await kvGet(env.DB, "parked")) as { done?: number[] } | null;
      if (p) await kvPut(env.DB, "parked", { ...p, done: [...new Set([...(p.done ?? []), id])].slice(-50) });
      if (id > 0) await env.DB.prepare("UPDATE inbox SET status='done' WHERE id=?").bind(id).run();
      return json({ ok: true });
    }

    // 공공 실시간 주차: 좌표 근처 1km 주차장 최대 3곳의 빈자리. 위치는 저장·로그하지 않는다
    if (m === "GET" && pathname === "/car/parking") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const q = new URL(req.url).searchParams;
      const lat = q.get("lat") ?? "", lng = q.get("lng") ?? "";
      if (lat.trim() === "" || lng.trim() === "" || Math.abs(Number(lat)) > 90 || Math.abs(Number(lng)) > 180 || !Number.isFinite(Number(lat)) || !Number.isFinite(Number(lng))) return json({ error: "bad lat/lng" }, 400);
      return json(await parkingNear(env.PARKING_API_KEY, parkingLots as Lot[], Number(lat), Number(lng)));
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

    // 폰 연결 앱이 주소록 이름·번호를 올린다 → 통화 뒤 자동 문자의 번호 찾기. 이름·번호는 로그에 안 찍는다
    if (m === "POST" && pathname === "/phone/contacts") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const list = ((await req.json().catch(() => ({}))) as Record<string, unknown>).contacts;
      if (!Array.isArray(list)) return json({ error: "contacts array required" }, 400);
      const map = new Map<string, string>();
      for (const c of list) {
        if (map.size >= 3000) break;
        const name = typeof c?.name === "string" ? c.name.trim() : "", num = String(c?.number ?? "").replace(/\D/g, "");
        if (name.length >= 1 && name.length <= 40 && /^01\d{8,9}$/.test(num) && !map.has(name)) map.set(name, num);
      }
      if (!list.length) await env.DB.prepare("DELETE FROM kv WHERE key='contacts'").run();
      else if (!map.size) return json({ error: "no valid contacts" }, 400);
      else await env.DB.prepare("INSERT INTO kv(key,value,updated_at) VALUES('contacts',?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at")
        .bind(JSON.stringify(Object.fromEntries(map)), now()).run();
      return json({ ok: true, count: map.size });
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

    if (m === "GET" && pathname === "/car/nearby") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const q = new URL(req.url).searchParams;
      return json(await nearby(env.KAKAO_REST_KEY, q.get("category") ?? "", q.get("lat") ?? "", q.get("lng") ?? ""));
    }

    if (m === "POST" && pathname === "/car/chat") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const b = (await req.json().catch(() => ({}))) as Record<string, unknown>;
      const text = typeof b.text === "string" ? b.text.trim().slice(0, 200) : "";
      if (!text) return json({ error: "text required" }, 400);
      const history = (Array.isArray(b.history) ? b.history : []).slice(-6)
        .filter((h): h is { role: "user" | "assistant"; text: string } => !!h && (h.role === "user" || h.role === "assistant") && typeof h.text === "string")
        .map((h) => ({ role: h.role, text: h.text.slice(0, 200) }));
      return json(await carChat(env.ANTHROPIC_API_KEY, text, history, (await kvGet(env.DB, "car_context").catch(() => null))?.room === "PARK"));
    }

    // 구글 캘린더 연결: 폰이 받은 인증 코드를 서버가 토큰으로 바꾼다(토큰은 폰·응답에 안 나간다)
    if (pathname === "/phone/google") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      if (m === "POST") {
        const code = ((await req.json().catch(() => ({}))) as Record<string, unknown>).code;
        if (typeof code !== "string" || !code) return json({ error: "code required" }, 400);
        const r = await connectGoogle(env, code, now());
        return json(r.body, r.status);
      }
      if (m === "GET") return json(await googleStatus(env));
      if (m === "DELETE") { await disconnectGoogle(env, now()); return json({ ok: true }); }
    }

    if (m === "GET" && pathname === "/car/schedule") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      await syncIfStale(env, now());
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
      // 촬영 기간: 요약이 끝난 통화 녹음을 지우지 않고 남긴다(기본 꺼짐 = 자동 삭제)
      if (m === "POST" && pathname === "/admin/keep-recordings") {
        const on = String((await req.formData()).get("on")) === "1";
        await env.DB.prepare("INSERT OR REPLACE INTO kv(key,value,updated_at) VALUES('keep_recordings',?,?)").bind(JSON.stringify(on), now()).run();
        return html(on ? "통화 녹음을 남깁니다(켜짐)" : "요약이 끝난 통화 녹음은 지웁니다(꺼짐)");
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
    await syncCalendar(env, now()).catch(() => {});
    for (const [sql, cut] of retention(now())) await env.DB.prepare(sql).bind(cut).run();
  },
};
