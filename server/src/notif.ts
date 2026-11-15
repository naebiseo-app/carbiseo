// 폰 알림 읽어주기(35 설계): 폰(연결 앱·관리 페이지) → inbox kind='notif', 차 답장 → kind='reply' 대기열 → 폰.
// 맡은 일: 알림·답장 본문 검증(parseNotify·parseReply)과 답장 대기열을 비우는 SQL. 저장과 경로 처리는 index.ts가 한다.
// 알림 본문은 개인 메시지라 console.log 금지.
// 먼저 읽을 함수: parseNotify → parseReply.
import { isBlocked } from "./guard.ts";

export type Notif = { app: string; sender: string; text: string; replyable: boolean; key: string };

const str = (v: unknown, max: number) => (typeof v === "string" ? v.trim().slice(0, max) : "");
const flag = (v: unknown) => v === true || ["1", "true", "on"].includes(String(v ?? "").trim().toLowerCase());

/** /phone/notify 본문(JSON 또는 관리 폼). 앱·보낸이·내용은 비면 거절하고, 넘치면 30자·30자·500자로 자른다. key = 폰 알림 키(답장 대상, 폼이면 빈 값). */
export function parseNotify(get: (k: string) => unknown): Notif | null {
  const app = str(get("app"), 30), sender = str(get("sender"), 30), text = str(get("text"), 500);
  if (!app || !sender || !text) return null;
  return { app, sender, text, replyable: flag(get("replyable")), key: str(get("key"), 300) };
}

/** /car/reply 본문: notif_id 숫자, 내용 1~500자, confirmed=true 필수(운전자가 되읽은 뒤 '보내'). */
export function parseReply(b: Record<string, unknown>) {
  const id = Number(b.notif_id);
  const text = str(b.text, 501); // 501자까지만 읽어 500자 초과를 거절로 알아본다(자르지 않는다)
  if (b.confirmed !== true) return { error: "confirmed must be true" };
  if (!Number.isInteger(id) || id <= 0) return { error: "bad notif_id" };
  if (text.length < 1 || text.length > 500) return { error: "bad text" };
  if (isBlocked(text, false)) return { error: "blocked text" }; // 사용자가 확인한 답장: 자르지 않고 금칙어만
  return { id, text };
}

// 폰이 가져가면 대기 → 전달로 바꾼다(한 번만 받게). 바꾸기와 꺼내기를 UPDATE 하나로 하므로 폴링이 겹쳐도 같은 답장을 두 번 주지 않는다. D1은 RETURNING 지원.
// 한계: 가져간 순간 '전달'이 되어 폰이 보내다 실패해도 다시 주지 않는다 (넘으려면 폰의 보냄 확인을 받은 뒤 전달로 바꾼다)
export const TAKE_REPLIES_SQL =
  "UPDATE inbox SET status='delivered' WHERE kind='reply' AND status='queued' RETURNING id,from_label,summary,meta";
