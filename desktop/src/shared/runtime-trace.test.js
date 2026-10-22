import test from "node:test";
import assert from "node:assert/strict";
import { orderedTrace, runtimeSummary, modelBudgetDecision } from "./runtime-trace.js";
test("same-run reordered events deduplicate by event identity", () => {
  const a = { payload: { event_id: "a", run_id: "run", sequence: 1 } }, b = { payload: { event_id: "b", run_id: "run", sequence: 2 } };
  assert.deepEqual(orderedTrace([b, a, a]), [a, b]);
});
test("interleaved runs each keep their own sequence without moving another run", () => {
  const a2={run_id:"a",sequence:2}, b1={run_id:"b",sequence:1}, a1={run_id:"a",sequence:1};
  assert.deepEqual(orderedTrace([a2,b1,a1]),[a1,b1,a2]);
});
test("tool outcomes remain associated to exact call and invocation", () => {
  const events = [
    { event_id: "1", kind: "tool_start", call_id: "a", invocation_id: "first", tool: "search" },
    { event_id: "2", kind: "tool_start", call_id: "b", invocation_id: "second", tool: "search" },
    { event_id: "3", kind: "tool_end", call_id: "b", invocation_id: "second", tool: "search", status: "FAILED", error_code: "TOOL_TIMEOUT", elapsed_ms: 30 },
  ];
  const summary = runtimeSummary(events);
  assert.equal(summary.tools, 2); assert.equal(summary.pendingTools, 1); assert.equal(summary.failedTools, 1);
  assert.equal(summary.invocations, 2); assert.equal(summary.calls[1].error, "TOOL_TIMEOUT");
});
test("estimated token budget never becomes supplier actual usage", () => {
  const event = { kind: "model_end", budget: { context_window: 65536, total_reserved: 12000, estimated: true }, usage: { total_tokens: 9 } };
  assert.equal(runtimeSummary([event]).usageReports, 0); assert.equal(modelBudgetDecision(event).usage, null);
  const actual = { ...event, usage_source: "provider_response" };
  assert.equal(runtimeSummary([actual, actual]).actualTokens, 18); // 无event_id的旧事件不能猜测是否重复。
  assert.equal(runtimeSummary([{ ...actual, event_id: "known" }, { ...actual, event_id: "known" }]).actualTokens, 9);
});
