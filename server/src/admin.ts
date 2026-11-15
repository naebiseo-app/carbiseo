import { guardText, FALLBACK } from "./guard.ts";
import { isUrgent } from "./script.ts";

// F6 데모 모드: 관리 페이지(/admin)용 순수 함수(DB·네트워크 없음). 이름·장소는 전부 가상.
// 맡은 일: Basic 인증 검사(basicOk), 관리 페이지 HTML(adminPage), 데모 일정(demoSchedule), 재현 통화 대본·빈 시간 계산(replayScript·freeSlots).
// 맡지 않는 일: 경로 처리·DB 저장(index.ts의 /admin/*).
// 먼저 읽을 함수: basicOk → replayScript → freeSlots.

// Basic 인증(브라우저 기본 로그인 창). 사용자 이름은 무엇이든, 비밀번호 = ADMIN_TOKEN. 토큰이 설정되지 않았으면 항상 거절한다(설정 누락 때 관리 페이지가 열리지 않게).
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

export type DemoEvent = { title: string; start: number; place: string; lat: number; lng: number; contact: string; contact_number: string }; // contact = 이름, contact_number = 가짜 번호(데모)

// 오늘 일정 3건: 지금부터 30분 단위로 올림한 시각 + 1h·3h·5h. 시연을 언제 하든 다음 일정이 남아 있게.
export function demoSchedule(nowMs: number): DemoEvent[] {
  const slot = 30 * 60_000;
  const base = Math.ceil(nowMs / slot) * slot;
  const h = 3_600_000;
  return [
    { title: "한빛물산 납품 미팅", start: base + h, place: "판교역", lat: 37.3948, lng: 127.1112, contact: "김민수 과장", contact_number: "01000000001" },
    { title: "새봄상사 견적 상담", start: base + 3 * h, place: "수원시청", lat: 37.2636, lng: 127.0286, contact: "이서연 대리", contact_number: "01000000002" },
    { title: "주간 팀 회의", start: base + 5 * h, place: "서울시청", lat: 37.5663, lng: 126.9779, contact: "박지훈 팀장", contact_number: "01000000003" },
  ];
}

// 장소 간 이동(분, 올림): 직선거리 ÷ 평균 30km/h. 한계: 도로·정체를 무시한다 (넘으려면 내비 ETA로 바꾼다)
export function travelMin(a: { lat: number; lng: number }, b: { lat: number; lng: number }): number {
  const r = Math.PI / 180;
  const h = Math.sin(((b.lat - a.lat) * r) / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin(((b.lng - a.lng) * r) / 2) ** 2;
  const km = 2 * 6371 * Math.asin(Math.sqrt(h));
  return Math.ceil((km / 30) * 60);
}

const hm = (ms: number) => new Date(ms + 9 * 3_600_000).toISOString().slice(11, 16);

// ── 재현 통화(도움 카드 세 가지: 추천 답변·사실 정정·빈 시간). 통화 망이 없어 서버가 대본을 차에 내려 주고 차가 상대 대사를 읽는다. ──
const KST = 9 * 3_600_000, HOUR = 3_600_000, MIN = 60_000, DAY = 86_400_000;
const WORK_FROM = 9, WORK_TO = 18; // 미팅은 업무시간(KST 09~18시, 주말 제외) 안에서만
export const MEET_MIN = 60; // 제안할 미팅 길이·일정 1건의 길이(일정에 끝 시각이 없어 같은 값으로 가정)
export const MEET_AT = { lat: 37.3948, lng: 127.1112 }; // 다음 미팅 장소 = 거래처(판교역)
export const REPLAY_NOTICE = "AI 비서가 함께 듣습니다";
// 가상 거래처 견적: 50개 × 개당 23만 원 = 1,150만 원
export const REPLAY_DEAL = { client: "한빛물산", contact: "김민수 과장", item: "사무용 의자 50개", unit: "개당 23만 원", total: "1,150만 원" };

export function korTime(ms: number): string {
  const d = new Date(ms + KST), h = d.getUTCHours(), m = d.getUTCMinutes();
  return `${h < 12 ? "오전" : "오후"} ${h % 12 || 12}시${m ? ` ${m}분` : ""}`;
}

export function slotLabel(ms: number, nowMs: number): string {
  const n = Math.floor((ms + KST) / DAY) - Math.floor((nowMs + KST) / DAY);
  const day = ["오늘", "내일", "모레"][n] ?? `${"일월화수목금토"[new Date(ms + KST).getUTCDay()]}요일`;
  return `${day} ${korTime(ms)}`;
}

/** 미팅 시작 후보 n개(기본 2). 조건: 업무시간 안·평일, 차가 도착한 뒤(지금+eta_min), 일정과 안 겹침(일정 앞뒤로 미팅 장소까지 이동시간 포함).
 *  둘째는 첫째보다 3시간 이상 뒤. 30분 단위로 오늘 포함 4일 안에서 찾는다.
 *  한계: 이동은 직선거리 ÷ 30km/h이고 현재 위치는 무시한다 (넘으려면 travelMin 자리에 경로 시간을 쓴다) */
export function freeSlots(s: DemoEvent[], nowMs: number, etaMin: number | null, n = 2): number[] {
  const earliest = nowMs + (etaMin ?? 0) * MIN, len = MEET_MIN * MIN;
  const ev = s.filter((e) => e.start + len > nowMs).map((e) => { const go = travelMin(e, MEET_AT) * MIN; return { from: e.start - go, to: e.start + len + go }; });
  const out: number[] = [];
  for (let d = 0; d < 4; d++) {
    const day0 = Math.floor((nowMs + KST) / DAY) * DAY - KST + d * DAY;
    const wd = new Date(day0 + KST).getUTCDay();
    if (wd === 0 || wd === 6) continue;
    for (let t = day0 + WORK_FROM * HOUR; t + len <= day0 + WORK_TO * HOUR; t += 30 * MIN) {
      if (t < earliest || ev.some((e) => t < e.to && e.from < t + len) || (out.length && t < out[0] + 3 * HOUR)) continue;
      out.push(t);
      if (out.length === n) return out;
    }
  }
  return out;
}

export type ReplayLine = { t: number; who: "ai" | "peer" | "driver" | "hint"; label: string; text: string };
export type Replay = { title: string; endMs: number; lines: ReplayLine[]; wrap: string };

// 대본(t = 시작 후 ms). 상대·AI 대사는 차가 읽고, 운전자 대사는 자막만, hint는 운전 중 귓속말 TTS·정차 중 카드.
// 도움 카드·정리 문장은 전부 가드(guardText)를 거친다. 일정·ETA가 달라지면 문장 속 시각도 달라진다.
export function replayScript(s: DemoEvent[], nowMs: number, etaMin: number | null): Replay {
  const D = REPLAY_DEAL;
  const ev = s.find((e) => e.contact.startsWith(D.contact.split(" ")[0]));
  const at = ev ? `${slotLabel(ev.start, nowMs)} ${ev.place}` : "";
  const [a, b] = freeSlots(s, nowMs, etaMin);
  const slots = b ? `${slotLabel(a, nowMs)}, 아니면 ${slotLabel(b, nowMs)}` : a ? slotLabel(a, nowMs) : "";
  const g = (x: string) => guardText("summary", x);
  const hint = (t: number, label: string, text: string): ReplayLine => ({ t, who: "hint", label, text: g(text) });
  const line = (t: number, who: ReplayLine["who"], text: string): ReplayLine => ({ t, who, label: "", text });
  const lines: ReplayLine[] = [
    line(0, "ai", REPLAY_NOTICE),
    line(3000, "peer", `여보세요, ${D.client} ${D.contact}입니다. ${D.item} 납품 일정이 어떻게 되는지 궁금해서 전화드렸어요.`),
    hint(9000, "추천 답변", ev ? `${at} 납품 미팅이 잡혀 있어요. 그때 일정 확정하자고 답하세요` : "납품 일정은 확인해서 오늘 안에 알려 드린다고 답하세요"),
    line(14000, "driver", ev ? `네 과장님, ${at}에서 뵐 때 납품 날짜 확정해서 말씀드릴게요.` : "네 과장님, 확인해서 바로 말씀드릴게요."),
    line(20000, "peer", "좋아요. 그런데 단가가 얼마였죠? 한 번 확인하고 싶어서요."),
    line(25000, "driver", "단가는 개당 25만 원으로 알고 있습니다."), // 일부러 틀린 말
    hint(30000, "사실 정정", `실제로는 ${D.unit}이에요 (${D.client} 견적 카드, 합계 ${D.total})`),
    line(35000, "driver", `아, 죄송합니다. ${D.unit}, 합계 ${D.total}입니다.`),
    line(41000, "peer", "네, 맞아요. 그럼 다음 미팅은 언제가 좋을까요? 편한 시간 두 개만 알려 주세요."),
    hint(47500, "빈 시간", slots ? `비는 시간은 ${slots}예요 (일정·이동 시간 반영)` : "이번 주엔 비는 시간이 없어요. 다음 주로 미루자고 답하세요"),
    line(53000, "driver", slots ? `${slots} 어떠세요?` : "이번 주는 어려워서 다음 주로 말씀드려도 될까요?"),
    line(59000, "peer", slots ? "그럼 첫 번째 시간으로 하죠. 그때 뵙겠습니다. 감사합니다." : "네, 다음 주에 다시 연락 주세요. 감사합니다."),
  ].filter((l) => l.text !== FALLBACK.summary);
  const promise = [ev ? `${at} 납품 일정 확정` : "납품 일정 확인", `단가 ${D.unit}(합계 ${D.total}) 재확인`, a ? `다음 미팅 ${slotLabel(a, nowMs)}` : "다음 미팅은 다음 주로"].join(" · ");
  return { title: `${D.client} ${D.contact}`, endMs: 66000, lines, wrap: g(`${D.client} ${D.contact} 통화 · 납품 일정, 단가, 다음 미팅 확인. 약속: ${promise}`) };
}

// 차에 내려 주는 형식(JSON 없이 줄 단위): 첫 줄 "제목\t끝ms", 다음 줄부터 "t\twho\tlabel\t문장"
export function encodeReplay(r: Replay): string {
  const c = (x: string) => x.replace(/[\t\r\n]+/g, " ");
  return [`${c(r.title)}\t${r.endMs}`, ...r.lines.map((l) => `${l.t}\t${l.who}\t${c(l.label)}\t${c(l.text)}`)].join("\n");
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

/** 관리 페이지 전체 HTML. msg는 맨 위에 한 줄로 보이고(이스케이프), replies·mails는 최근 목록. stt-dict·resummarize·demo-clock은 화면에 폼이 없고 POST로 직접 부른다. */
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
<form method="post" action="/admin/keep-recordings"><h2>촬영 기간: 통화 녹음 남기기</h2><p>기본은 요약이 끝나면 녹음을 자동 삭제합니다. 켜면 지우지 않습니다. 촬영 뒤 끄세요.</p><button name="on" value="1">켜기</button><button name="on" value="0">끄기</button></form>
<form method="post" action="/admin/reset-inbox"><h2>부재중 카드 비우기</h2><p>녹화 전에 inbox를 모두 지웁니다.</p><button>비우기</button></form>
<form method="post" action="/admin/replay"><h2>재현 통화 시작</h2><p>거래처 통화를 재현합니다. 차가 상대 대사를 읽고(운전자 대사는 자막만), 정차 중엔 화면 아래 도움 카드, 운전 중엔 귓속말 한 줄. 약 1분, 끝나면 통화 정리 카드가 남습니다.</p><button>재현 통화 시작</button></form>`;
}
