// 통화 받아쓰기(ClawOps WhisperX) 핫워드 사전을 주인 일정·연락처 이름 + 업무 낱말로 맞춘다.
// 11-10 실측(같은 녹음, 로컬 large-v3): 핫워드 없이 '세금 상사·본적서·경력서' → 핫워드로 '새봄상사·견적서'. 요금제 안, 추가 비용 없음.
import { kvGet, namesFrom } from "./index.ts";

type E = { DB: D1Database; CLAWOPS_API_KEY?: string; CLAWOPS_ACCOUNT_ID?: string };

// 차 안 업무 전화에 자주 나오는데 받아쓰기가 잘 틀리는 낱말
export const WORK_WORDS = ["견적서", "견적", "납품", "발주", "계약서", "세금계산서", "거래명세서", "미팅", "회의", "일정", "입금", "결제", "배송", "샘플", "도면"];

export function hotwords(contacts: unknown, schedule: unknown): string[] {
  const words = namesFrom(contacts, schedule).flatMap((n) => n.split(/\s+/)).filter((w) => /^[가-힣A-Za-z0-9]{2,20}$/.test(w));
  return [...new Set([...words, ...WORK_WORDS])].slice(0, 80); // 한계: 80개 상한, 연락처가 많으면 앞쪽만. 통화 상대 빈도순이 필요하면 그때
}

export async function syncDict(env: E): Promise<string> {
  if (!env.CLAWOPS_API_KEY || !env.CLAWOPS_ACCOUNT_ID) return "no key";
  const base = `https://api.claw-ops.com/v1/accounts/${env.CLAWOPS_ACCOUNT_ID}/dictionaries`;
  const call = (path: string, method = "GET", body?: unknown) =>
    fetch(base + path, { method, headers: { Authorization: `Bearer ${env.CLAWOPS_API_KEY}`, "content-type": "application/json" }, body: body ? JSON.stringify(body) : undefined });
  let id: string | null = await kvGet(env.DB, "stt_dict");
  let items: { id: string; term: string }[] | null = null;
  if (id) { const r = await call(`/${id}`); items = r.ok ? ((await r.json()) as any).items ?? [] : null; }
  if (!items) {
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
