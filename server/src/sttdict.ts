// 통화 받아쓰기(ClawOps WhisperX) 핫워드 사전을 주인 일정·연락처 이름 + 업무 낱말로 맞춘다.
// 11-10 실측(같은 녹음, 로컬 large-v3): 핫워드 없이 '세금 상사·본적서·경력서' → 핫워드로 '새봄상사·견적서'. 요금제 안, 추가 비용 없음.
// 맡은 일: 핫워드 목록 만들기(hotwords)와 ClawOps 사전과의 차이만 올리고 지우기(syncDict).
// 맡지 않는 일: 받아쓰기 요약과 이름 교정(index.ts의 namesFrom·fixNames). 실행 시점은 index.ts: 연락처를 올릴 때·/admin/stt-dict·매일 보관 정책 때.
// 먼저 읽을 함수: syncDict.
import { kvGet, namesFrom } from "./index.ts";

type E = { DB: D1Database; CLAWOPS_API_KEY?: string; CLAWOPS_ACCOUNT_ID?: string };

// 차 안 업무 전화에 자주 나오는데 받아쓰기가 잘 틀리는 낱말
export const WORK_WORDS = ["견적서", "견적", "납품", "발주", "계약서", "세금계산서", "거래명세서", "미팅", "회의", "일정", "입금", "결제", "배송", "샘플", "도면"];

/** 연락처·일정 이름을 공백으로 나눠 한글·영문·숫자 2~20자 낱말만 고르고 업무 낱말을 합친다(중복 제거, 최대 80개). */
export function hotwords(contacts: unknown, schedule: unknown): string[] {
  const words = namesFrom(contacts, schedule).flatMap((n) => n.split(/\s+/)).filter((w) => /^[가-힣A-Za-z0-9]{2,20}$/.test(w));
  return [...new Set([...words, ...WORK_WORDS])].slice(0, 80); // 한계: 80개를 넘으면 앞쪽만 남는다 (넘으려면 통화 상대 빈도순으로 고른다)
}

/** ClawOps 사전을 hotwords와 같게 맞춘다: 없는 낱말은 추가, 목록에 없는 낱말은 삭제. 결과 문자열 = "no key" | "create <HTTP 상태>" | "ok <낱말 수>". 사전 id는 kv 'stt_dict'에 둔다. 호출이 던지면 부른 쪽이 잡는다. */
export async function syncDict(env: E): Promise<string> {
  if (!env.CLAWOPS_API_KEY || !env.CLAWOPS_ACCOUNT_ID) return "no key";
  const base = `https://api.claw-ops.com/v1/accounts/${env.CLAWOPS_ACCOUNT_ID}/dictionaries`;
  const call = (path: string, method = "GET", body?: unknown) =>
    fetch(base + path, { method, headers: { Authorization: `Bearer ${env.CLAWOPS_API_KEY}`, "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
  let id: string | null = await kvGet(env.DB, "stt_dict");
  let items: { id: string; term: string }[] | null = null;
  if (id) { const r = await call(`/${id}`); items = r.ok ? ((await r.json()) as any).items ?? [] : null; }
  if (!items) { // 저장된 사전이 없거나 읽지 못하면 새로 만들어 기본 사전(isDefault)으로 지정한다
    const r = await call("", "POST", { name: "내비서 일정·연락처" });
    if (!r.ok) return `create ${r.status}`;
    id = ((await r.json()) as any).id as string;
    await call(`/${id}`, "PATCH", { isDefault: true });
    await env.DB.prepare("INSERT OR REPLACE INTO kv(key,value,updated_at) VALUES('stt_dict',?,?)").bind(JSON.stringify(id), Date.now()).run();
    items = [];
  }
  const want = new Set(hotwords(await kvGet(env.DB, "contacts"), await kvGet(env.DB, "schedule")));
  const have = new Set(items.map((i) => i.term));
  for (const t of want) if (!have.has(t)) await call(`/${id}/terms`, "POST", { term: t });
  for (const i of items) if (!want.has(i.term)) await call(`/${id}/terms/${i.id}`, "DELETE");
  return `ok ${want.size}`;
}
