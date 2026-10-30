import { cleanName, parseEta, parseRoom, pickScript } from "./script.ts";
import { adminPage, basicOk, demoSchedule, parseFakeCall, REPLAY_SAID, replayCards } from "./admin.ts";

interface Env {
  DB: D1Database;
  CAR_TOKEN: string;
  CALL_CONTEXT_SECRET: string;
  CLAWOPS_SIGNING_KEY?: string; // 아직 없으면 서명 검증은 'nokey'로 기록만
  ADMIN_TOKEN?: string; // 없으면 /admin 전부 401
  CLAWOPS_ACCOUNT_ID?: string;
  CLAWOPS_API_KEY?: string;
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
  const from = (f.get("From") ?? "").replace(/\D/g, "");
  // ponytail: 기본 요약 스키마엔 급함 필드가 없어 낱말로 판정. 오판이 잦으면 조직 스키마에 urgent 필드 추가
  const text = [summary, ...(j.decisions ?? []), ...(j.followUps ?? [])].join(" ");
  const urgent = /급하|긴급|바로 연락|즉시/.test(text) && !/급하지 않|긴급하지 않/.test(text) ? 1 : 0;
  return { call_id: f.get("CallId") ?? "", from_label: from.length >= 4 ? `끝번호 ${from.slice(-4)}` : "", summary, urgent };
}

/** 받은 전화의 AI 통화가 끝났으면 그 CallId. */
export function transcriptTarget(raw: string) {
  const f = new URLSearchParams(raw);
  return f.get("Event") === "agent.disconnected" && f.get("Direction") !== "outbound" ? f.get("CallId") || null : null;
}

const DAY = 86_400_000;
export const retention = (t: number): [string, number][] => [
  ["UPDATE inbox SET raw=NULL WHERE raw IS NOT NULL AND created_at < ?", t - 7 * DAY],
  ["DELETE FROM inbox WHERE created_at < ?", t - 30 * DAY],
];

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(req.url);
    const m = req.method;
    const authed = req.headers.get("authorization") === `Bearer ${env.CAR_TOKEN}`;

    if (m === "POST" && pathname === "/car/state") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const b = (await req.json().catch(() => ({}))) as { room?: unknown; eta_min?: unknown };
      const room = parseRoom(b.room);
      if (!room) return json({ error: "room must be PARK|DRIVE_WORK|DRIVE_REST" }, 400);
      const eta_min = parseEta(b.eta_min);
      await env.DB.prepare("INSERT INTO kv(key,value,updated_at) VALUES('car_context',?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at")
        .bind(JSON.stringify({ room, eta_min }), now()).run();
      return json({ ok: true, room, eta_min });
    }

    // 차 앱 설정: 내 호칭·AI 비서 이름
    if (m === "POST" && pathname === "/car/settings") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const b = (await req.json().catch(() => ({}))) as Record<string, unknown>;
      const cur = (await kvGet(env.DB, "settings")) ?? {};
      for (const k of ["owner_label", "assistant_name"] as const) {
        if (!(k in b)) continue;
        const v = cleanName(b[k]);
        if (!v) return json({ error: `bad ${k}` }, 400);
        cur[k] = v;
      }
      await env.DB.prepare("INSERT OR REPLACE INTO kv(key,value,updated_at) VALUES('settings',?,?)").bind(JSON.stringify(cur), Date.now()).run();
      return json({ ok: true, settings: cur });
    }

    if (m === "GET" && pathname === "/car/inbox") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const since = Number(new URL(req.url).searchParams.get("since") ?? 0) || 0;
      const { results } = await env.DB.prepare("SELECT * FROM inbox WHERE id>? AND created_at<=? ORDER BY id DESC LIMIT 50").bind(since, now()).all();
      return json({ items: results });
    }

    // callContextUrl: 비밀 조각 경로. 어떤 실패에도 유효한 응답을 돌려 통화가 끊기지 않게 한다.
    if (m === "POST" && pathname.startsWith("/clawops/call-context/")) {
      if (pathname.slice("/clawops/call-context/".length) !== env.CALL_CONTEXT_SECRET) return json({ error: "not found" }, 404);
      const raw = await req.text();
      let room = null as ReturnType<typeof parseRoom>;
      let owner: string | undefined;
      let name: string | undefined;
      let eta: number | null = null;
      try {
        const cc = await kvGet(env.DB, "car_context");
        room = cc?.room ?? null;
        eta = parseEta(cc?.eta_min);
        const st = await kvGet(env.DB, "settings");
        owner = st?.owner_label;
        name = st?.assistant_name;
        await saveInbox(env.DB, "ring", "ringing", room, raw, { ct: req.headers.get("content-type") });
      } catch (e) {
        console.log("call-context db error", String(e));
      }
      const s = pickScript(room, owner, name, eta);
      return json({ CallContext: { Instruction: s.instruction, Variables: { room: s.room } } });
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
      const card = sig === "ok" ? summaryCard(raw) : null;
      if (card) {
        const cc = await kvGet(env.DB, "car_context");
        // 웹훅 재전송 대비: 같은 통화의 카드는 1장만
        await env.DB.prepare("INSERT INTO inbox(kind,status,room,call_id,from_label,summary,urgent,created_at) SELECT 'call','summarized',?,?,?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM inbox WHERE kind='call' AND call_id=?)")
          .bind(parseRoom(cc?.room) ?? null, card.call_id, card.from_label, card.summary, card.urgent, now(), card.call_id).run();
      }
      return json({ ok: true });
    }

    if (m === "GET" && pathname === "/car/schedule") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      return json({ items: (await kvGet(env.DB, "schedule")) ?? [] });
    }

    // F6 데모 모드 관리 페이지. 브라우저 기본 로그인 창(비밀번호 = ADMIN_TOKEN).
    if (pathname === "/admin" || pathname.startsWith("/admin/")) {
      if (!basicOk(req.headers.get("authorization"), env.ADMIN_TOKEN))
        return new Response("unauthorized", { status: 401, headers: { "www-authenticate": 'Basic realm="carbiseo-admin", charset="UTF-8"' } });
      const html = (msg: string, status = 200) => new Response(adminPage(msg), { status, headers: { "content-type": "text/html; charset=utf-8" } });
      if (m === "GET" && pathname === "/admin") return html("");
      if (m === "POST" && pathname === "/admin/fake-call") {
        const c = parseFakeCall(await req.formData());
        if (!c) return html("입력 확인: 이름 1~10자(글자·숫자·공백), 용건 1~120자", 400);
        const room = (await kvGet(env.DB, "car_context"))?.room ?? null;
        await env.DB.prepare("INSERT INTO inbox(kind,status,room,from_label,summary,urgent,created_at,raw,meta) VALUES('call','demo',?,?,?,?,?,?,?)")
          .bind(room, c.from_label, c.summary, c.urgent, now(), JSON.stringify(c), JSON.stringify({ demo: true })).run();
        return html(`넣었습니다: ${c.from_label} / ${c.summary}`);
      }
      if (m === "POST" && pathname === "/admin/reset-schedule") {
        const s = demoSchedule(now());
        await env.DB.prepare("INSERT OR REPLACE INTO kv(key,value,updated_at) VALUES('schedule',?,?)").bind(JSON.stringify(s), now()).run();
        return html(`일정 ${s.length}건으로 초기화했습니다`);
      }
      if (m === "POST" && pathname === "/admin/replay") {
        const t = now();
        const s = (await kvGet(env.DB, "schedule")) ?? demoSchedule(t);
        const room = (await kvGet(env.DB, "car_context"))?.room ?? null;
        const cards = replayCards(s, t);
        await env.DB.batch(cards.map((c) => env.DB.prepare("INSERT INTO inbox(kind,status,room,from_label,summary,created_at,meta) VALUES('hint','replay',?,?,?,?,?)")
          .bind(room, c.label, c.text, c.created_at, JSON.stringify({ step: c.step, said: REPLAY_SAID }))));
        return html(`재현 카드 ${cards.length}장을 넣었습니다(8초 간격)`);
      }
    }

    return json({ error: "not found" }, 404);
  },
  // 보관 정책: 웹훅 원문(raw)은 7일 뒤 비우고, 행 전체는 30일 뒤 삭제 (wrangler.toml crons, 매일 1회)
  async scheduled(_e: unknown, env: Env) {
    for (const [sql, cut] of retention(now())) await env.DB.prepare(sql).bind(cut).run();
  },
};
