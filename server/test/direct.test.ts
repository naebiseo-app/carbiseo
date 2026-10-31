import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";
import worker, { signData } from "../src/index.ts";

// D1 흉내: 실제 schema.sql을 메모리 SQLite에
function d1() {
  const db = new DatabaseSync(":memory:");
  db.exec(readFileSync(new URL("../schema.sql", import.meta.url), "utf8"));
  return {
    db,
    prepare(sql: string) {
      let args: any[] = [];
      const st = {
        bind(...a: any[]) { args = a; return st; },
        async run() { db.prepare(sql).run(...args); return { meta: { changes: 1 } }; },
        async first() { return db.prepare(sql).get(...args) ?? null; },
        async all() { return { results: db.prepare(sql).all(...args) }; },
      };
      return st;
    },
  };
}

const OWNER = "01011112222", VIP = "01055556789", OTHER = "01077778888"; // 가상 번호
const CID = "CAe210942f21a47ab8e7eb36072fff5480", CID2 = "CAe210942f21a47ab8e7eb36072fff5481";
const mkEnv = () => ({ DB: d1(), CAR_TOKEN: "t", CALL_CONTEXT_SECRET: "cc", OWNER_NUMBER: OWNER, CLAWOPS_SIGNING_KEY: "k" }) as any;
const settings = (env: any, body: unknown) =>
  worker.fetch(new Request("https://x/car/settings", { method: "POST", headers: { authorization: "Bearer t" }, body: JSON.stringify(body) }), env);
const ring = async (env: any, from: string, id = CID) =>
  ((await (await worker.fetch(new Request("https://x/clawops/call-context/cc", { method: "POST", body: `CallId=${id}&From=${from}` }), env)).json()) as any).CallContext;
const report = (env: any, args: object) =>
  worker.fetch(new Request("https://x/mcp/cc", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "tools/call", params: { name: "report_call", arguments: args } }) }), env);
const cards = (env: any) => env.DB.db.prepare("SELECT * FROM inbox WHERE kind='call' ORDER BY id").all() as any[];
const LINE = "바로 연결 목록에 넣은 분입니다";

test("direct_numbers: 저장·마스킹·삭제·잘못된 값 400", async () => {
  const env = mkEnv();
  const r = (await (await settings(env, { direct_numbers: ["010-5555-6789", "0212345678"] })).json()) as any;
  assert.deepEqual(r.settings.direct_labels, ["****6789", "****5678"]);
  assert.equal(r.settings.direct_numbers, undefined); // 원본 번호는 응답에 없음
  assert.deepEqual(JSON.parse(env.DB.db.prepare("SELECT value FROM kv WHERE key='settings'").get().value).direct_numbers, [VIP, "0212345678"]);
  for (const bad of ["01055556789", ["12345"], [1055556789], [VIP, "abc"], Array(11).fill(VIP)])
    assert.equal((await settings(env, { direct_numbers: bad })).status, 400);
  const d = (await (await settings(env, { direct_numbers: [] })).json()) as any;
  assert.equal(d.settings.direct_labels, undefined);
  assert.equal(JSON.parse(env.DB.db.prepare("SELECT value FROM kv WHERE key='settings'").get().value).direct_numbers, undefined);
});

test("call-context: 목록 번호면 direct 줄·Variables.direct, 아니면 없음, ment 모드는 줄 없음", async () => {
  const env = mkEnv();
  await settings(env, { owner_label: "만일", direct_numbers: [VIP] });
  const a = await ring(env, VIP);
  assert.ok(a.Instruction.includes(`이 분은 만일님이 ${LINE}. 용건만 짧게 듣고 '바로 전해 드리겠습니다'로 끝냅니다.`));
  assert.equal(a.Variables.direct, "1");
  const b = await ring(env, "+82" + VIP.slice(1), CID2); // +82 표기도 같은 번호
  assert.equal(b.Variables.direct, "1");
  const o = await ring(env, OTHER);
  assert.ok(!o.Instruction.includes(LINE));
  assert.equal(o.Variables.direct, undefined);
  await settings(env, { mode: "ment" });
  const m = await ring(env, VIP);
  assert.ok(!m.Instruction.includes(LINE));
});

test("카드: report_call·summary.completed 모두 목록 번호면 urgent=1·meta.direct", async () => {
  const env = mkEnv();
  await settings(env, { direct_numbers: [VIP] });
  await ring(env, VIP);
  await report(env, { call_id: CID, summary: "견적 문의" });
  await ring(env, OTHER, CID2);
  await report(env, { call_id: CID2, summary: "견적 문의" });
  const raw = new URLSearchParams({ CallId: "CAe210942f21a47ab8e7eb36072fff5482", From: VIP, Direction: "inbound", Event: "summary.completed", SummaryJson: JSON.stringify({ coreSummary: "안부 전화" }) }).toString();
  const mac = await crypto.subtle.sign("HMAC", await crypto.subtle.importKey("raw", new TextEncoder().encode("k"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]),
    new TextEncoder().encode(signData("https://x/clawops/events", null, raw)));
  await worker.fetch(new Request("https://x/clawops/events", { method: "POST", headers: { "x-signature": btoa(String.fromCharCode(...new Uint8Array(mac))) }, body: raw }), env);
  const c = cards(env);
  assert.equal(c.length, 3);
  assert.deepEqual([c[0].urgent, JSON.parse(c[0].meta).direct, JSON.parse(c[0].meta).via], [1, true, "mcp"]); // 기존 meta와 합쳐짐
  assert.deepEqual([c[1].urgent, JSON.parse(c[1].meta).direct], [0, undefined]);
  assert.deepEqual([c[2].urgent, JSON.parse(c[2].meta).direct], [1, true]);
});

test("ment_text: 저장·따옴표 괄호 제거·ment 대본 반영·빈 값이면 기본 문구·잘못된 값 400", async () => {
  const env = mkEnv();
  await settings(env, { mode: "ment" });
  assert.ok((await ring(env, OTHER)).Instruction.includes("'운전 중이라 받을 수 없습니다. 정차 후 연락드리겠습니다.'라고만"));
  const r = (await (await settings(env, { ment_text: " 지금 회의 중이에요. '곧' (연락) 드릴게요! " })).json()) as any;
  assert.equal(r.settings.ment_text, "지금 회의 중이에요. 곧 연락 드릴게요!");
  const ins = (await ring(env, OTHER)).Instruction;
  assert.ok(ins.includes("'지금 회의 중이에요. 곧 연락 드릴게요!'라고만 말하고, 다른 말에는 답하지 않고 바로 통화를 종료합니다."));
  assert.ok(!ins.includes("운전 중이라"));
  for (const bad of ["가".repeat(61), "()", "a\nb", "a; b", "a:b", 5, "a<b>"])
    assert.equal((await settings(env, { ment_text: bad })).status, 400);
  assert.equal((await settings(env, { ment_text: "" })).status, 200);
  assert.ok((await ring(env, OTHER)).Instruction.includes("운전 중이라 받을 수 없습니다."));
});
