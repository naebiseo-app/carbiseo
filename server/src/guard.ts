// LLM 가드(설계 §6): AI가 만든 문장은 저장 전에 길이 120자·금칙어 검사. 실패하면 고정 문구로 대체.
export const MAX_AI_TEXT = 120;
export const FALLBACK = { summary: "통화 내용을 정리하지 못했어요. 직접 확인해 주세요.", draft: "초안을 만들지 못했어요.", chat: "잠깐 연결이 안 돼요. 조금 뒤에 다시 말씀해 주세요." } as const;

// 작은 목록. 늘리려면 여기에만 추가.
export const BANNED_WORDS = ["씨발", "시발", "씹", "병신", "개새끼", "좆", "지랄", "미친놈", "미친년", "꺼져", "닥쳐", "죽어라", "죽여버", "한남충", "김치녀", "맘충", "틀딱", "장애인새끼"];

/** 금칙어·전화번호/주민번호처럼 보이는 숫자열(구분자 - . 허용, 숫자 9자리 이상) 포함 여부. 사용자가 확인한 본문(/car/send 등)은 numbers=false — 회신 번호를 적는 문자는 정상이다. */
export function isBlocked(text: string, numbers = true): boolean {
  const flat = text.replace(/\s+/g, "");
  if (BANNED_WORDS.some((w) => flat.includes(w))) return true;
  // 한계: 시각·날짜(8자리)는 통과하려고 공백 구분("010 0000 0000")과 한글 숫자(공일공)는 못 잡음
  return numbers && (text.match(/\d(?:[.-]?\d)+/g) ?? []).some((m) => m.replace(/\D/g, "").length >= 9);
}

/** 120자 이하로: 문장 단위로 자르고 마침표로 끝낸다('…' 없음). 첫 문장이 120자를 넘으면 그 문장을 잘라 마침표. */
export function clip(text: string, max = MAX_AI_TEXT): string {
  const t = text.trim();
  if (t.length <= max) return t;
  const sents = t.match(/[^.!?。]+[.!?。]*\s*/g) ?? [t];
  let out = "";
  for (const s of sents) { if ((out + s).trim().length > max) break; out += s; }
  out = out.trim();
  if (!out) out = t.slice(0, max - 1).trim().replace(/[\s,.!?]+$/, "") + ".";
  return out;
}

/** AI 생성 문장 저장 전 가드. 빈 문장·금칙어·번호 포함이면 고정 문구, 길면 문장 단위로 자름. */
export function guardText(kind: keyof typeof FALLBACK, text: string | null | undefined): string {
  const t = (text ?? "").trim();
  return !t || isBlocked(t) ? FALLBACK[kind] : clip(t);
}
