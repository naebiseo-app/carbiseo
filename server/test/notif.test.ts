import test from "node:test";
import assert from "node:assert/strict";
import { parseNotify, parseReply } from "../src/notif.ts";
import { adminPage, repliesHtml } from "../src/admin.ts";

test("폰 알림 입력: JSON·폼 같은 규칙, 빈 값 거절", () => {
  const j = (o: Record<string, unknown>) => parseNotify((k) => o[k]);
  assert.deepEqual(j({ app: " 카카오톡 ", sender: "김부장", text: "회의 자료 보냈어요", replyable: true, key: "0|com.kakao.talk|1" }),
    { app: "카카오톡", sender: "김부장", text: "회의 자료 보냈어요", replyable: true, key: "0|com.kakao.talk|1" });
  const f = new URLSearchParams({ app: "문자", sender: "엄마", text: "저녁 먹고 와?", replyable: "1" });
  assert.equal(parseNotify((k) => f.get(k))!.replyable, true);
  assert.equal(parseNotify((k) => new URLSearchParams({ app: "문자", sender: "엄마", text: "x" }).get(k))!.replyable, false); // 체크 안 함
  assert.equal(j({ app: "카카오톡", sender: "", text: "x" }), null);
  assert.equal(j({ app: "카카오톡", sender: "김부장", text: "  " }), null);
  assert.equal(j({ app: 1, sender: "김부장", text: "x" }), null);
  assert.equal(j({ app: "카카오톡", sender: "김부장", text: "가".repeat(900) })!.text.length, 500);
});

test("답장: 확인(confirmed=true)·알림 id·내용 길이", () => {
  assert.deepEqual(parseReply({ notif_id: 7, text: " 지금 운전 중, 이따 연락할게요 ", confirmed: true }), { id: 7, text: "지금 운전 중, 이따 연락할게요" });
  assert.ok("error" in parseReply({ notif_id: 7, text: "네", confirmed: "true" }));
  assert.ok("error" in parseReply({ notif_id: 0, text: "네", confirmed: true }));
  assert.ok("error" in parseReply({ notif_id: "x", text: "네", confirmed: true }));
  assert.ok("error" in parseReply({ notif_id: 7, text: "", confirmed: true }));
  assert.ok("error" in parseReply({ notif_id: 7, text: "가".repeat(501), confirmed: true }));
});

test("관리 페이지: 시험 알림 폼, 보낼 답장 목록(이스케이프)", () => {
  assert.match(adminPage(), /action="\/admin\/fake-notif"/);
  assert.match(repliesHtml([]), /아직 없습니다/);
  const h = repliesHtml([{ from_label: "김부장", summary: "<b>네</b>", status: "queued" }, { from_label: "엄마", summary: "곧 가요", status: "delivered" }]);
  assert.match(h, /김부장님께 “&lt;b&gt;네&lt;\/b&gt;” · 보낼 답장/);
  assert.match(h, /엄마님께 “곧 가요” · 폰에 전달/);
});
