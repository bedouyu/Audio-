// UI 预检与服务端 ScriptReviewPolicy 使用同一组要求；服务端仍复核权限、状态与最新报告。
export const REVIEW_CHECKS = Object.freeze({
  topic: "主题", genre: "体裁", language: "语言", ending: "结尾完整", length: "篇幅与时长",
  citations: "引用依据", readable: "适合朗读",
});

function problemsOf(review) {
  const problems = [];
  if (typeof review.approved !== "boolean") problems.push("缺少明确的复核结论");
  if (typeof review.reason !== "string" || !review.reason.trim()) problems.push("缺少复核说明");
  if (!Array.isArray(review.issues) || review.issues.length > 20 || review.issues.some(item => typeof item !== "string" || !item.trim()))
    problems.push("复核问题列表格式不完整");
  const checks = review.checks;
  if (!checks || typeof checks !== "object" || Array.isArray(checks) || Object.keys(REVIEW_CHECKS).some(key => typeof checks[key] !== "boolean"))
    problems.push("七项检查未逐项完成");
  if (review.approved === true && (Object.keys(REVIEW_CHECKS).some(key => checks?.[key] !== true) || review.issues?.length))
    problems.push("仍有未通过项或待解决问题，不能判为通过");
  return problems;
}

export function isApprovedReview(artifact, review) {
  return !!review && typeof review.id === "string" && !!review.id && review.run_id === artifact.run_id
    && review.reviewed_artifact_id === artifact.id && review.content_hash === artifact.content_hash
    && review.approved === true && problemsOf(review).length === 0;
}

export function boundReview(artifact, artifacts) {
  if (artifact.kind !== "SCRIPT_DRAFT" || !artifact.run_id) return null;
  // API 按创建时间倒序返回。带时间的集合也按时间和 ID 排序，避免列表顺序改变结论。
  const reports = artifacts.map((report, index) => ({ report, index })).sort((a, b) => {
    const first = instantOrder(a.report.created_at), second = instantOrder(b.report.created_at);
    if (first && second)
      return second.localeCompare(first) || String(b.report.id).localeCompare(String(a.report.id));
    return a.index - b.index;
  });
  for (const { report } of reports) {
    if (report.kind !== "REVIEW_REPORT" || report.run_id !== artifact.run_id) continue;
    try {
      const review = JSON.parse(report.content);
      if (!review || typeof review !== "object" || review.reviewed_artifact_id !== artifact.id || review.content_hash !== artifact.content_hash) continue;
      const validationProblems = problemsOf(review);
      const timestamp = instantOrder(report.created_at);
      if (timestamp && reports.some(({ report: other }) => {
        if (other === report || other.kind !== "REVIEW_REPORT" || other.run_id !== artifact.run_id || instantOrder(other.created_at) !== timestamp) return false;
        try {
          const value = JSON.parse(other.content);
          return value?.reviewed_artifact_id === artifact.id && value?.content_hash === artifact.content_hash;
        } catch { return false; }
      })) validationProblems.push("旧复核报告的时间相同，无法确认先后，请重新复核当前稿件");
      // 最新绑定报告无效时不能回退借用旧的“通过”结论。
      return { ...review, id: report.id, run_id: report.run_id, valid: validationProblems.length === 0,
        approved: review.approved === true && validationProblems.length === 0, validationProblems };
    } catch { /* 无法识别绑定关系的旧报告不作为批准凭证。 */ }
  }
  return null;
}

function instantOrder(value) {
  // Instant 保留到纳秒。Date.parse 会截掉毫秒后的位数，可能把相邻两次复核顺序颠倒。
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(String(value || ""));
  return match ? match[1] + "." + (match[2] || "").padEnd(9, "0") : "";
}
export function approvalPayload(artifact, review, voice, overrideReason = "") {
  if (!/^[0-9a-f]{64}$/.test(artifact.content_hash || "")) throw new Error("缺少当前内容校验值，请刷新产物");
  const value = { confirmed: true, expected_content_hash: artifact.content_hash, voice };
  if (artifact.kind === "SCRIPT_DRAFT") {
    if (isApprovedReview(artifact, review)) {
      value.review_id = review.id;
    } else {
      const reason = overrideReason.trim();
      if (reason.length < 10 || reason.length > 500) throw new Error("人工覆盖复核需填写10至500字理由");
      value.override_review = true; value.override_reason = reason;
    }
  }
  return value;
}
