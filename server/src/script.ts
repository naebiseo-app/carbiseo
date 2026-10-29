export type Room = "PARK" | "DRIVE_WORK" | "DRIVE_REST";
export const ROOMS: Room[] = ["PARK", "DRIVE_WORK", "DRIVE_REST"];

// 모든 방 공통 규칙 (설계서 ④)
const COMMON =
  "당신은 {owner}님 휴대폰을 대신 받는 AI 비서입니다. 존댓말로 한 번에 한 문장씩 짧게 말합니다. " +
  "상대의 이름, 용건 한 줄, 급한 정도(급한지 아닌지)를 물어 기록합니다. " +
  "약속이나 일정을 확정하지 않고, 금액·가격·계약은 답하지 않으며, {owner}님의 위치는 말하지 않습니다. " +
  "못 알아들으면 한 번만 다시 묻고, 그래도 안 되면 '메모로 남겨 두겠습니다'라고 정리합니다.";

const BY_ROOM: Record<Room, string> = {
  DRIVE_WORK:
    "지금 {owner}님은 운전 중입니다. '지금 운전 중이라 정차 후 직접 연락드리겠습니다'라고 안내하고, 용건과 회신 희망 시각을 받은 뒤 마무리합니다.",
  DRIVE_REST:
    "오늘 업무는 마치셨습니다. '오늘 업무는 마치셨습니다. 내일 오전에 연락드리도록 전해 드리겠습니다'라고 안내합니다. 상대가 급하다고 하면 '급한 건으로 표시해 두겠습니다'라고 말합니다.",
  PARK: "지금 {owner}님은 잠시 자리를 비우셨습니다. '잠시 자리를 비우셨습니다. 용건 남겨 주시면 곧 회신드리겠습니다'라고 안내하고 용건을 받습니다.",
};

export function parseRoom(v: unknown): Room | null {
  return ROOMS.includes(v as Room) ? (v as Room) : null;
}

// 첫 마디: 누구의 AI 비서인지 밝힌다(AI임을 숨기지 않음). 이름은 사용자가 정한다.
export function greeting(owner: string, name?: string): string {
  const who = name ? `${owner}님의 AI 비서 ${name}입니다` : `${owner}님의 AI 비서입니다`;
  return `전화가 연결되면 첫 마디로 정확히 '안녕하세요, ${who}. 통화 내용은 요약해서 ${owner}님께 전해 드립니다.'라고 말합니다.`;
}

// 주행 방 + 도착 예정(분)이 있으면 방 안내를 이것으로 바꾼다.
const ETA_LINE =
  "지금 {owner}님은 운전 중입니다. '지금 운전 중이라 약 {eta}분 뒤 도착 예정입니다. 도착 후 연락드리도록 전해 드리겠습니다'라고 안내하고, 용건을 받은 뒤 마무리합니다. 상대가 급하다고 하면 '급한 건으로 표시해 두겠습니다'라고 말합니다.";

// 차 앱이 보낸 남은 분. 0~1440 정수만, 그 밖은 없음.
export function parseEta(v: unknown): number | null {
  return Number.isInteger(v) && (v as number) >= 0 && (v as number) <= 1440 ? (v as number) : null;
}

// 방 상태가 없으면 가장 보수적인 DRIVE_WORK 대본
export function pickScript(room: Room | null, owner = "주인", name?: string, eta?: number | null): { room: Room; instruction: string } {
  const r = room ?? "DRIVE_WORK";
  const byRoom = r !== "PARK" && eta != null ? ETA_LINE.replace("{eta}", String(eta)) : BY_ROOM[r];
  return { room: r, instruction: `${greeting(owner, name)} ${COMMON} ${byRoom}`.replaceAll("{owner}", owner) };
}

// 사용자 입력 검증: 1~10자, 글자·숫자·공백만(대본 주입 방지)
export function cleanName(v: unknown): string | null {
  if (typeof v !== "string") return null;
  const t = v.trim();
  return /^[\p{L}\p{N} ]{1,10}$/u.test(t) ? t : null;
}
