import test from "node:test";
import assert from "node:assert/strict";
import { memoryDecision } from "./memory-trace.js";

test("memory card shows the actual source envelope and checkpoint warnings", () => {
  const memory = { strategy: "hybrid", fragments: [{source_id: "m12", role: "user", text: "不要公式"}],
    total_budget_chars: 6000, scanned_messages: 40, checkpoint_loaded: true, warnings: ["RECENT_EXCERPT"],
    checkpoint_warnings: ["扫描有界"] };
  const result = memoryDecision({payload: {kind: "memory_prepare", memory}});
  assert.equal(result.characters, JSON.stringify(memory).length);
  assert.equal(result.fragments[0].source_id, "m12");
  assert.deepEqual(result.warnings, ["RECENT_EXCERPT", "扫描有界"]);
});
test("malformed or unrelated memory events are ignored", () => {
  assert.equal(memoryDecision({kind: "tool_end"}), null);
  assert.equal(memoryDecision({kind: "memory_prepare", memory: {strategy: "shell", fragments: []}}), null);
});
