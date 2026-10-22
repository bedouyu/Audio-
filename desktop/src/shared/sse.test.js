import test from "node:test";
import assert from "node:assert/strict";
import { createSseParser, createStreamCompletionGuard } from "./sse.js";
test("preserves frames split inside JSON and CRLF boundaries", () => {
  const events = [];
  const accept = createSseParser((event) => events.push(event));
  accept('data: {"type":"TOKEN","data":"你');
  accept('好"}\r');
  accept('\n\r\ndata: {"type":"DONE","data":""}\n\n');
  assert.deepEqual(
    events.map((e) => e.type),
    ["TOKEN", "DONE"],
  );
  assert.equal(events[0].data, "你好");
});

const result = () => ({ type: "RESULT", data: {
  answer: "原回答", session_id: "session", run_id: "run", artifacts: [], status: "SUCCEEDED",
} });
test("completion needs a real result followed by DONE", () => {
  const guard = createStreamCompletionGuard();
  guard.accept({ type: "TOKEN", data: "片段" });
  assert.throws(() => guard.finish());
  guard.accept(result());
  assert.throws(() => guard.finish());
  guard.accept({ type: "DONE" });
  assert.doesNotThrow(() => guard.finish());
});
test("DONE without RESULT and errors cannot mark a task complete", () => {
  assert.throws(() => createStreamCompletionGuard().accept({ type: "DONE" }));
  assert.throws(() => createStreamCompletionGuard().accept({ type: "ERROR", data: "任务失败" }), /任务失败/);
});
test("malformed results and output after a result are rejected", () => {
  const invalid = result(); delete invalid.data.run_id;
  assert.throws(() => createStreamCompletionGuard().accept(invalid), /不完整/);
  const guard = createStreamCompletionGuard(); guard.accept(result());
  assert.throws(() => guard.accept({ type: "TOKEN", data: "迟到文本" }));
  guard.accept({ type: "DONE" });
  assert.throws(() => guard.accept({ type: "DONE" }));
});
