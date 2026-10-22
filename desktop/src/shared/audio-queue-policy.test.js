import test from "node:test";
import assert from "node:assert/strict";
import { canRefill, queueMessage, segmentConfirmation, audioSubmission } from "./audio-queue-policy.js";
const pending = () => ({ mode: "CONTINUOUS", status: "READY", storage: "mysql-durable", generating: 0, segments: [{ status: "PENDING" }] });
test("legacy and uncertain queues never automatically refill", () => {
  assert.equal(canRefill(pending()), true);
  for (const status of ["NEEDS_REVIEW", "FAILED", "PREPARING", "LEGACY_READ_ONLY", "CANCELLED"])
    assert.equal(canRefill({ ...pending(), status }, true), false);
  assert.equal(canRefill({ ...pending(), segments: [{ status: "NEEDS_REVIEW" }, { status: "PENDING" }] }, true), false);
});
test("single queue refill needs a separate explicit action", () => {
  const queue = { ...pending(), mode: "SINGLE" };
  assert.equal(canRefill(queue), false); assert.equal(canRefill(queue, true), true);
  assert.match(queueMessage({ status: "PREPARING" }), /尚未完成/);
});
test("recovery and reset bind exact retained attempt with human reason", () => {
  const segment = { status: "NEEDS_REVIEW", script_hash: "a".repeat(64), audio_id: "7ab79a00-2127-4c91-890d-674ab100001f" };
  assert.equal(segmentConfirmation(segment).expected_script_hash, segment.script_hash);
  assert.throws(() => segmentConfirmation(segment, "short", true));
  assert.throws(() => segmentConfirmation({ ...segment, status: "READY" }));
  assert.equal(segmentConfirmation(segment, "人工已检查原文件，明确重置新的合成尝试", true).confirmed, true);
});
test("same failed submission keeps key while changed parameters create a new key", () => {
  const original = audioSubmission({ project_id: 7, voice: "a" }, null, () => "key-a");
  assert.equal(audioSubmission({ project_id: 7, voice: "a" }, original, () => "unused"), original);
  assert.equal(audioSubmission({ project_id: 7, voice: "b" }, original, () => "key-b").body.request_id, "key-b");
});
