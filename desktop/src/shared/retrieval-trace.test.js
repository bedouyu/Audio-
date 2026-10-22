import test from "node:test";
import assert from "node:assert/strict";
import { retrievalDecision } from "./retrieval-trace.js";
test("retrieval choice exposes actual fallback without pretending it is RSE", () => {
  const result = retrievalDecision({ payload: { tool: "knowledge_search", kind: "tool_end", observation: JSON.stringify({
    requested_retrieval: "hybrid", requested_context: "rse_rank_proxy", actual_retrieval: "legacy_dense",
    actual_context: "legacy_top_k", strategy_reason: "流程需要连续段", hit_count: 2,
    context_tokens_bge: 600, warnings: ["缺少坐标，已降级", 1],
  }) }});
  assert.equal(result.actual, "legacy_dense / legacy_top_k");
  assert.deepEqual(result.warnings, ["缺少坐标，已降级"]);
});
test("invalid or unrelated observations are ignored", () => {
  assert.equal(retrievalDecision({ tool: "knowledge_search", kind: "tool_end", observation: "bad-json" }), null);
  assert.equal(retrievalDecision({ tool: "web_search", kind: "tool_end", observation: "{}" }), null);
});
