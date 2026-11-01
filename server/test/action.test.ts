import test from "node:test";
import assert from "node:assert/strict";
import { parseInboxAction } from "../src/index.ts";

test("카드 버튼 결과: done·tomorrow만, id는 양의 정수", () => {
  assert.deepEqual(parseInboxAction("/car/inbox/12", { action: "done" }), { id: 12, status: "done" });
  assert.deepEqual(parseInboxAction("/car/inbox/7", { action: "tomorrow" }), { id: 7, status: "tomorrow" });
  for (const [p, a] of [["/car/inbox/12", "delete"], ["/car/inbox/abc", "done"], ["/car/inbox/0", "done"], ["/car/inbox/1.5", "done"]]) assert.equal(parseInboxAction(p, { action: a }), null);
});
