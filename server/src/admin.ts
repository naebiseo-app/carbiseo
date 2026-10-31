import { isUrgent } from "./script.ts";

// F6 데모 모드: 관리 페이지(/admin)용 순수 함수. 이름·장소는 전부 가상.

// Basic 인증(브라우저 기본 로그인 창). 사용자 이름은 무엇이든, 비밀번호 = ADMIN_TOKEN.
export function basicOk(header: string | null, token: string | undefined): boolean {
  if (!token || !header?.startsWith("Basic ")) return false;
  try {
    const dec = atob(header.slice(6));
    return dec.slice(dec.indexOf(":") + 1) === token;
  } catch {
    return false;
  }
}

export type FakeCall = { from_label: string; summary: string; urgent: number };

// 발신자 이름 1~10자(글자·숫자·공백), 용건 1~120자.
export function parseFakeCall(f: { get(k: string): unknown }): FakeCall | null {
  const name = String(f.get("from_label") ?? "").trim();
  const summary = String(f.get("summary") ?? "").trim();
  if (!/^[\p{L}\p{N} ]{1,10}$/u.test(name) || summary.length < 1 || summary.length > 120) return null;
  return { from_label: name, summary, urgent: !["", "0", "false", "off"].includes(String(f.get("urgent") ?? "").trim().toLowerCase()) || isUrgent(summary) ? 1 : 0 }; // 체크 또는 실제 요약과 같은 낱말 판정
}

export type DemoEvent = { title: string; start: number; place: string; lat: number; lng: number };

// 오늘 일정 3건: 지금부터 30분 단위로 올림한 시각 + 1h·3h·5h. 시연을 언제 하든 다음 일정이 남아 있게.
export function demoSchedule(nowMs: number): DemoEvent[] {
  const slot = 30 * 60_000;
  const base = Math.ceil(nowMs / slot) * slot;
  const h = 3_600_000;
  return [
    { title: "한빛물산 납품 미팅", start: base + h, place: "판교역", lat: 37.3948, lng: 127.1112 },
    { title: "새봄상사 견적 상담", start: base + 3 * h, place: "수원시청", lat: 37.2636, lng: 127.0286 },
    { title: "주간 팀 회의", start: base + 5 * h, place: "서울시청", lat: 37.5663, lng: 126.9779 },
  ];
}

// 재현 통화(원문③④⑤)용 가상 견적 데이터
export const DEMO_QUOTE = { client: "새봄상사", item: "사무용 의자 50개", amount: "1,150만 원" };
export const REPLAY_SAID = "다음 주 화요일 2시 미팅 가능하세요? 지난번 견적 1,200만 원 맞죠?";

// 장소 간 이동(분): 직선거리 ÷ 평균 30km/h. ponytail: 도로·정체 무시, 실제 경로가 필요하면 내비 ETA로 교체
export function travelMin(a: { lat: number; lng: number }, b: { lat: number; lng: number }): number {
  const r = Math.PI / 180;
  const h = Math.sin(((b.lat - a.lat) * r) / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(((b.lng - a.lng) * r) / 2) ** 2;
  const km = 2 * 6371 * Math.asin(Math.sqrt(h));
  return Math.ceil((km / 30) * 60);
}

// 일정 사이 첫 빈 시간. 다음 장소까지 이동시간을 빼고 30분 이상 남는 구간.
// ponytail: 일정에 끝 시각이 없어 각 30분으로 가정. 끝 시각이 생기면 그것으로
export function freeSlot(s: DemoEvent[], nowMs: number, durMs = 30 * 60_000) {
  const ev = s.filter((e) => e.start + durMs > nowMs).sort((a, b) => a.start - b.start);
  for (let i = 0; i + 1 < ev.length; i++) {
    const move = travelMin(ev[i], ev[i + 1]);
    const from = Math.max(ev[i].start + durMs, nowMs), to = ev[i + 1].start - move * 60_000;
    if (to - from >= 30 * 60_000) return { from, to, move };
  }
  return null;
}

const hm = (ms: number) => new Date(ms + 9 * 3_600_000).toISOString().slice(11, 16);

// 거래처 통화 재현 카드 3장(+0·+8·+16초)
export function replayCards(s: DemoEvent[], nowMs: number) {
  const f = freeSlot(s, nowMs);
  return [
    { label: "추천 답변", text: "화요일 2시는 판교 미팅과 겹쳐요. 4시 가능하다고 답하세요" },
    { label: "사실 정정", text: `견적은 ${DEMO_QUOTE.amount}입니다 (${DEMO_QUOTE.client} ${DEMO_QUOTE.item})` },
    { label: "빈 시간", text: f ? `${hm(f.from)}~${hm(f.to)} 비어요(이동 ${f.move}분 포함)` : "오늘 남은 빈 시간이 없어요" },
  ].map((c, i) => ({ ...c, step: i + 3, created_at: nowMs + i * 8000 }));
}

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);

export type ReplyRow = { from_label: string; summary: string; status: string };

// B안 '보낼 답장': queued = 아직 폰이 안 가져감, delivered = 연결 앱이 가져감
export function repliesHtml(rows: ReplyRow[]): string {
  if (!rows.length) return "<p>아직 없습니다.</p>";
  return "<ul>" + rows.map((r) => `<li>${esc(r.from_label)}님께 “${esc(r.summary)}” · ${r.status === "queued" ? "보낼 답장" : "폰에 전달"}</li>`).join("") + "</ul>";
}

export type MailRow = { from_label: string; summary: string; created_at: number; meta: string };

// 데모 메일함: 차에서 '보낸' 메일(실제 발송 없음). 받는 사람·제목·본문 앞 80자·시각
export function mailsHtml(rows: MailRow[]): string {
  if (!rows.length) return "<p>아직 없습니다.</p>";
  return "<ul>" + rows.map((r) => {
    let subject = "";
    try { subject = JSON.parse(r.meta).subject ?? ""; } catch { /* 제목 없음 */ }
    return `<li>${esc(r.from_label)}님께 · ${esc(subject)} · ${esc((r.summary ?? "").slice(0, 80))} · ${hm(r.created_at)}</li>`;
  }).join("") + "</ul>";
}

export function adminPage(msg = "", replies: ReplyRow[] = [], mails: MailRow[] = []): string {
  return `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>내비서 데모</title>
<style>body{font:18px sans-serif;max-width:560px;margin:24px auto;padding:0 16px;background:#F3EEE4;color:#2B2620}
input,button{font:inherit;padding:10px;margin:4px 0;width:100%;box-sizing:border-box}button{background:#C2683A;color:#fff;border:0;border-radius:8px}
form{background:#FFFDF8;padding:16px;border-radius:14px;margin:16px 0}.m{font-weight:bold}</style>
<h1>내비서 데모 모드</h1><p class="m">${esc(msg)}</p>
<form method="post" action="/admin/fake-call"><h2>가짜 수신 넣기</h2>
<input name="from_label" placeholder="발신자 이름 (가상, 10자 이내)" maxlength="10" required>
<input name="summary" placeholder="용건 (120자 이내)" maxlength="120" required>
<label><input type="checkbox" name="urgent" style="width:auto"> 급함</label>
<button>넣기</button></form>
<form method="post" action="/admin/fake-notif"><h2>시험 알림 보내기</h2><p>폰에 메시지 알림이 온 것처럼 차에 넣습니다(보낸이는 가상 이름).</p>
<input name="app" placeholder="앱 (예: 카카오톡, 문자)" maxlength="30" value="카카오톡" required>
<input name="sender" placeholder="보낸이 (예: 김부장, 엄마)" maxlength="30" required>
<input name="text" placeholder="내용 (500자 이내)" maxlength="500" required>
<label><input type="checkbox" name="replyable" value="1" checked style="width:auto"> 답장 가능</label>
<button>알림 보내기</button></form>
<form><h2>보낼 답장</h2><p>차에서 음성으로 만든 답장입니다.</p>${repliesHtml(replies)}</form>
<form><h2>데모 메일함</h2><p>차에서 보낸 메일입니다(실제로는 발송되지 않고 여기에만 저장).</p>${mailsHtml(mails)}</form>
<form method="post" action="/admin/reset-schedule"><h2>데모 일정 초기화</h2><p>오늘 일정 3건(지금부터 1·3·5시간 뒤)으로 덮어씁니다.</p><button>초기화</button></form>
<form method="post" action="/admin/owner-as-guest"><h2>녹화용: 주인 번호를 손님으로</h2><p>켜면 주인 번호로 건 전화도 일반 발신자처럼 AI가 응대합니다. 녹화 뒤 꼭 끄세요.</p><button name="on" value="1">켜기</button><button name="on" value="0">끄기</button></form>
<form method="post" action="/admin/reset-inbox"><h2>부재중 카드 비우기</h2><p>녹화 전에 inbox를 모두 지웁니다.</p><button>비우기</button></form>
<form method="post" action="/admin/replay"><h2>재현 통화 시작</h2><p>거래처 통화("${esc(REPLAY_SAID)}")에 맞춰 차에 카드 3장을 8초 간격으로 띄웁니다.</p><button>재현 통화 시작</button></form>`;
}
