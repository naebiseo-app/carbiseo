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
  return { from_label: name, summary, urgent: f.get("urgent") ? 1 : 0 };
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

const esc = (s: string) => s.replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[c]!);

export function adminPage(msg = ""): string {
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
<form method="post" action="/admin/reset-schedule"><h2>데모 일정 초기화</h2><p>오늘 일정 3건(지금부터 1·3·5시간 뒤)으로 덮어씁니다.</p><button>초기화</button></form>`;
}
