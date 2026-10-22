import test from "node:test";
import assert from "node:assert/strict";
import { boundReview, approvalPayload } from "./approval-policy.js";
const artifact = {id: "a", kind: "SCRIPT_DRAFT", content_hash: "b".repeat(64)};
test("approval binds exact target and hash, not just a report input ID", () => {
  const report = {id: "r", kind: "REVIEW_REPORT", input_ids: ["a"], content: JSON.stringify({
    reviewed_artifact_id: "a", content_hash: artifact.content_hash, approved: true})};
  const review = boundReview(artifact, [report]);
  assert.equal(approvalPayload(artifact, review, "voice").review_id, "r");
  report.content = JSON.stringify({reviewed_artifact_id: "other", content_hash: artifact.content_hash, approved: true});
  assert.equal(boundReview(artifact, [report]), null);
});
test("missing or rejected review requires explicit reason; stale hashes never pass", () => {
  assert.throws(() => approvalPayload(artifact, null, "voice"), /10至500/);
  const value = approvalPayload(artifact, null, "voice", "我已人工阅读并承担此草稿的执行决定");
  assert.equal(value.override_review, true);
  assert.equal(value.expected_content_hash, artifact.content_hash);
  assert.throws(() => approvalPayload({...artifact, content_hash: undefined}, null), /刷新/);
});
