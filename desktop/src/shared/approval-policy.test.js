import test from "node:test";
import assert from "node:assert/strict";
import { boundReview, approvalPayload, REVIEW_CHECKS, isApprovedReview } from "./approval-policy.js";
const artifact = { id: "a", run_id: "run", kind: "SCRIPT_DRAFT", content_hash: "b".repeat(64) };
const checks = Object.fromEntries(Object.keys(REVIEW_CHECKS).map(key => [key, true]));
function report(id = "r", overrides = {}, at = "2026-10-24T00:00:00Z") {
  return { id, run_id: "run", kind: "REVIEW_REPORT", input_ids: ["a"], created_at: at, content: JSON.stringify({
    reviewed_artifact_id: "a", content_hash: artifact.content_hash, approved: true, reason: "逐项核查通过", checks, issues: [], ...overrides }) };
}
test("approval binds exact target, run and hash with complete structured checks", () => {
  const review = boundReview(artifact, [report()]);
  assert.equal(approvalPayload(artifact, review, "voice").review_id, "r");
  assert.equal(isApprovedReview(artifact, review), true);
  for (const invalid of [report("r", { reviewed_artifact_id: "other" }), report("r", { content_hash: "c".repeat(64) }), { ...report(), run_id: "other" }])
    assert.equal(boundReview(artifact, [invalid]), null);
});
test("latest bound rejection or invalid report never borrows an earlier approval", () => {
  const older = report("old");
  for (const changes of [{ approved: false, issues: ["缺少结尾"] }, { checks: {} }, { issues: ["仍需调整"] }, { reason: 123 }]) {
    const latest = report("latest", changes, "2026-10-24T00:01:00Z");
    for (const outputs of [[older, latest], [latest, older]]) {
      const review = boundReview(artifact, outputs);
      assert.equal(review.id, "latest"); assert.equal(review.approved, false);
      assert.throws(() => approvalPayload(artifact, review, "voice"), /10至500/);
    }
  }
});
test("missing check, false check and unresolved issue do not permit forged approval payloads", () => {
  for (const changes of [{ checks: undefined }, { checks: { ...checks, ending: false } }, { issues: ["待修订"] }]) {
    const raw = { ...JSON.parse(report("r", changes).content), id: "r", run_id: "run" };
    assert.equal(isApprovedReview(artifact, raw), false);
    assert.throws(() => approvalPayload(artifact, raw, "voice"), /10至500/);
  }
});
test("a newer unrelated report cannot hide the current script's review", () => {
  const unrelated = report("other", { reviewed_artifact_id: "different" }, "2026-10-24T00:02:00Z");
  assert.equal(boundReview(artifact, [unrelated, report()]).id, "r");
});
test("sub-millisecond report timestamps preserve the actual review order", () => {
  const older = report("z-old", {}, "2026-10-24T00:00:00.123456Z");
  const latest = report("a-new", { approved: false, issues: ["待修订"] }, "2026-10-24T00:00:00.123457Z");
  assert.equal(boundReview(artifact, [older, latest]).id, "a-new");
  assert.equal(boundReview(artifact, [latest, older]).approved, false);
});
test("legacy reports sharing one timestamp cannot be approved by random ID order", () => {
  for (const outputs of [[report("z-passed"), report("a-rejected", { approved: false })],
    [report("a-passed"), report("z-rejected", { approved: false })]]) {
    const review = boundReview(artifact, outputs);
    assert.equal(review.approved, false);
    assert.match(review.validationProblems.join(" "), /时间相同/);
    assert.throws(() => approvalPayload(artifact, review, "voice"), /10至500/);
  }
});
test("missing or rejected review requires explicit reason; stale hashes never pass", () => {
  assert.throws(() => approvalPayload(artifact, null, "voice"), /10至500/);
  const value = approvalPayload(artifact, null, "voice", "我已人工阅读并确认此草稿的执行决定");
  assert.equal(value.override_review, true);
  assert.equal(value.expected_content_hash, artifact.content_hash);
  assert.throws(() => approvalPayload({...artifact, content_hash: undefined}, null), /刷新/);
});
