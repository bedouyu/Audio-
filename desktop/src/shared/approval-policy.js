// 与服务端审核绑定字段一致；这是UI预检，不替代服务端哈希/权限/状态检查。
export function boundReview(artifact, artifacts) {
  for (const report of artifacts) {
    if (report.kind !== "REVIEW_REPORT" || !report.input_ids?.includes(artifact.id)) continue;
    try {
      const review = JSON.parse(report.content);
      if (review.reviewed_artifact_id === artifact.id && review.content_hash === artifact.content_hash)
        return { ...review, id: report.id };
    } catch { /* 旧版或损坏报告不当作当前版本的批准。 */ }
  }
  return null;
}
export function approvalPayload(artifact, review, voice, overrideReason = "") {
  if (!/^[0-9a-f]{64}$/.test(artifact.content_hash || "")) throw new Error("缺少当前内容校验值，请刷新产物");
  const value = { confirmed: true, expected_content_hash: artifact.content_hash, voice };
  if (artifact.kind === "SCRIPT_DRAFT") {
    if (review?.approved === true && review.content_hash === artifact.content_hash && review.reviewed_artifact_id === artifact.id) {
      value.review_id = review.id;
    } else {
      const reason = overrideReason.trim();
      if (reason.length < 10 || reason.length > 500) throw new Error("人工覆盖复核需填写10至500字理由");
      value.override_review = true; value.override_reason = reason;
    }
  }
  return value;
}
