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

const OWNER = "01011112222", GUEST = "01055556789"; // 가상 번호
const CID = "CAe210942f21a47ab8e7eb36072fff5480";
const mkEnv = () => ({ DB: d1(), CAR_TOKEN: "t", CALL_CONTEXT_SECRET: "cc", OWNER_NUMBER: OWNER, CLAWOPS_SIGNING_KEY: "k" }) as any;
const rpc = (env: any, body: object, path = "/mcp/cc") =>
  worker.fetch(new Request("https://x" + path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ jsonrpc: "2.0", ...body }) }), env);
const ring = async (env: any, from: string, id = CID) =>
  ((await (await worker.fetch(new Request("https://x/clawops/call-context/cc", { method: "POST", body: `CallId=${id}&From=${from}` }), env)).json()) as any).CallContext;
const report = (env: any, args: object) => rpc(env, { id: 3, method: "tools/call", params: { name: "report_call", arguments: args } }).then((r) => r.json() as any);
const cards = (env: any) => env.DB.db.prepare("SELECT * FROM inbox WHERE kind='call'").all() as any[];

test("MCP: initialize·알림·tools/list, 비밀 틀리면 404, GET 405", async () => {
  const env = mkEnv();
  const i = (await (await rpc(env, { id: 1, method: "initialize", params: { protocolVersion: "2025-03-26" } })).json()) as any;
  assert.equal(i.result.protocolVersion, "2025-03-26");
  assert.deepEqual(i.result.capabilities, { tools: {} });
  assert.equal((await rpc(env, { method: "notifications/initialized" })).status, 202);
  const l = (await (await rpc(env, { id: 2, method: "tools/list" })).json()) as any;
  assert.deepEqual(l.result.tools.map((t: any) => t.name), ["report_call"]);
  assert.deepEqual(l.result.tools[0].inputSchema.required, ["call_id"]);
  assert.equal((await rpc(env, { id: 1, method: "initialize" }, "/mcp/nope")).status, 404);
  assert.equal((await worker.fetch(new Request("https://x/mcp/cc"), env)).status, 405);
});

test("report_call: 잘못된 call_id는 isError, 카드 없음", async () => {
  const env = mkEnv();
  for (const id of ["CA1", "../x", "", "CAzz0942f21a47ab8e7eb36072fff5480"]) {
    const r = await report(env, { call_id: id, summary: "x" });
    assert.equal(r.result.isError, true, id);
  }
  assert.equal(cards(env).length, 0);
});

test("report_call → 카드 1장, 뒤에 온 summary.completed는 건너뜀", async () => {
  const env = mkEnv();
  await ring(env, GUEST);
  const r = await report(env, { call_id: CID, caller: "한빛물산 김민수", purpose: "견적", urgent: false, callback_by: "", summary: "한빛물산 김민수, 견적 회신 급히 요청" });
  assert.equal(r.result.isError, false);
  assert.match(r.result.content[0].text, /통화를 끊지 말고/);
  await report(env, { call_id: CID, summary: "한빛물산 김민수, 견적 회신 급히 요청" }); // 같은 내용 재전송은 1장 그대로
  const raw = new URLSearchParams({ CallId: CID, From: GUEST, Direction: "inbound", Event: "summary.completed", SummaryJson: JSON.stringify({ coreSummary: "나중 요약" }) }).toString();
  const mac = await crypto.subtle.sign("HMAC", await crypto.subtle.importKey("raw", new TextEncoder().encode("k"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]),
    new TextEncoder().encode(signData("https://x/clawops/events", null, raw)));
  await worker.fetch(new Request("https://x/clawops/events", { method: "POST", headers: { "x-signature": btoa(String.fromCharCode(...new Uint8Array(mac))) }, body: raw }), env);
  const c = cards(env);
  assert.equal(c.length, 1);
  assert.equal(c[0].summary, "한빛물산 김민수, 견적 회신 급히 요청");
  assert.equal(c[0].from_label, "한빛물산 김민수");
  assert.equal(c[0].from_number, GUEST);
  assert.equal(c[0].urgent, 1); // 입력 false여도 요약에 '급히'
  assert.equal(c[0].status, "summarized");
});

test("report_call: X-ClawOps-Call-Id 헤더가 있으면 AI가 적은 call_id보다 우선", async () => {
  const env = mkEnv();
  await ring(env, GUEST);
  const r = await worker.fetch(new Request("https://x/mcp/cc", { method: "POST", headers: { "x-clawops-call-id": CID },
    body: JSON.stringify({ jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "report_call", arguments: { call_id: "CA틀림", summary: "견적 문의" } } }) }), env);
  assert.equal(((await r.json()) as any).result.isError, false);
  assert.equal(cards(env)[0].call_id, CID);
});

test("report_call 두 번: 뒤 내용으로 교체(새 id, meta.replaces), 카드는 1장", async () => {
  const env = mkEnv();
  await ring(env, GUEST);
  await report(env, { call_id: CID, caller: "김민수", summary: "미팅 변경" });
  const first = cards(env).filter((c) => c.status === "summarized")[0];
  await report(env, { call_id: CID, caller: "김민수", summary: "미팅 변경, 자료도 요청" });
  const now = cards(env).filter((c) => c.call_id === CID);
  assert.equal(now.length, 1);
  assert.equal(now[0].summary, "미팅 변경, 자료도 요청");
  assert.equal(now[0].status, "summarized");
  assert.equal(JSON.parse(now[0].meta).replaces, first.id);
});

test("report_call: 주인 통화는 카드 없이 ok(스위치 켜면 손님처럼)", async () => {
  const env = mkEnv();
  await ring(env, OWNER);
  assert.match((await report(env, { call_id: CID, summary: "x" })).result.content[0].text, /기록했습니다/);
  assert.equal(cards(env).length, 0);
  env.DB.db.prepare("INSERT INTO kv(key,value,updated_at) VALUES('owner_as_guest','true',0)").run();
  await report(env, { call_id: CID, summary: "x" });
  assert.equal(cards(env).length, 1);
});

test("call-context: 손님 대본 끝에 report_call 줄(CallId 포함), 주인·CallId 없음은 제외", async () => {
  const env = mkEnv();
  assert.match((await ring(env, GUEST)).Instruction, new RegExp(`report_call 도구를 말없이 한 번 호출합니다\\(call_id: "${CID}"`));
  assert.doesNotMatch((await ring(env, GUEST, "")).Instruction, /report_call/);
  assert.doesNotMatch((await ring(env, OWNER)).Instruction, /report_call/);
  env.DB.db.prepare("INSERT INTO kv(key,value,updated_at) VALUES('owner_as_guest','true',0)").run();
  assert.match((await ring(env, OWNER)).Instruction, /report_call/);
});
