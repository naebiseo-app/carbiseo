import { cleanName, parseRoom, pickScript } from "./script.ts";

interface Env {
  DB: D1Database;
  CAR_TOKEN: string;
  CALL_CONTEXT_SECRET: string;
  CLAWOPS_SIGNING_KEY?: string; // 아직 없으면 서명 검증은 'nokey'로 기록만
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

const hex = (b: ArrayBuffer) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
const b64 = (b: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(b)));

// 인코딩 미확인: hex/base64 둘 다 시도. 결과 = hex | base64 | fail | nokey | nosig
async function checkSig(env: Env, raw: string, sig: string | null) {
  if (!env.CLAWOPS_SIGNING_KEY) return "nokey";
  if (!sig) return "nosig";
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(env.CLAWOPS_SIGNING_KEY), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(raw));
  const s = sig.replace(/^sha256=/, "").trim();
  if (s.toLowerCase() === hex(mac)) return "hex";
  if (s === b64(mac)) return "base64";
  return "fail";
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const { pathname } = new URL(req.url);
    const m = req.method;
    const authed = req.headers.get("authorization") === `Bearer ${env.CAR_TOKEN}`;

    if (m === "POST" && pathname === "/car/state") {
      if (!authed) return json({ error: "unauthorized" }, 401);
      const room = parseRoom(((await req.json().catch(() => ({}))) as { room?: unknown }).room);
      if (!room) return json({ error: "room must be PARK|DRIVE_WORK|DRIVE_REST" }, 400);
      await env.DB.prepare("INSERT INTO kv(key,value,updated_at) VALUES('car_context',?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at")
        .bind(JSON.stringify({ room }), now()).run();
      return json({ ok: true, room });
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
      const { results } = await env.DB.prepare("SELECT * FROM inbox WHERE id>? ORDER BY id DESC LIMIT 50").bind(since).all();
      return json({ items: results });
    }

    // callContextUrl: 비밀 조각 경로. 어떤 실패에도 유효한 응답을 돌려 통화가 끊기지 않게 한다.
    if (m === "POST" && pathname.startsWith("/clawops/call-context/")) {
      if (pathname.slice("/clawops/call-context/".length) !== env.CALL_CONTEXT_SECRET) return json({ error: "not found" }, 404);
      const raw = await req.text();
      let room = null as ReturnType<typeof parseRoom>;
      let owner: string | undefined;
      let name: string | undefined;
      try {
        room = (await kvGet(env.DB, "car_context"))?.room ?? null;
        const st = await kvGet(env.DB, "settings");
        owner = st?.owner_label;
        name = st?.assistant_name;
        await saveInbox(env.DB, "call", "ringing", room, raw, { ct: req.headers.get("content-type") });
      } catch (e) {
        console.log("call-context db error", String(e));
      }
      const s = pickScript(room, owner, name);
      return json({ CallContext: { Instruction: s.instruction, Variables: { room: s.room } } });
    }

    if (m === "POST" && pathname === "/clawops/events") {
      const raw = await req.text();
      const sig = await checkSig(env, raw, req.headers.get("x-signature"));
      console.log("clawops webhook sig=" + sig, "ct=" + req.headers.get("content-type"));
      await saveInbox(env.DB, "event", "received", null, raw, { ct: req.headers.get("content-type"), sig, sig_header: req.headers.get("x-signature") });
      return json({ ok: true });
    }

    return json({ error: "not found" }, 404);
  },
};
