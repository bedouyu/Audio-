export function canRefill(queue, manual = false) {
  if (!queue || queue.storage === "legacy-redis-read-only" || queue.generating) return false;
  if (!["READY", "COMPLETE"].includes(queue.status)) return false;
  if (queue.mode === "SINGLE" && !manual) return false;
  const segments = Array.isArray(queue.segments) ? queue.segments : [];
  return !segments.some(s => s.status === "NEEDS_REVIEW" || s.status === "SYNTHESIZING")
    && segments.some(s => s.status === "PENDING");
}
export function queueMessage(queue) {
  if (!queue) return "";
  const messages = {
    PREPARING: "正在整理内容，尚未完成音频。", GENERATING: "正在准备分段音频…",
    READY: "文字稿已保存；未完成片段可手动继续生成。", COMPLETE: "音频已准备完成，可以收听。",
    NEEDS_REVIEW: "有结果未知的尝试，先核验原音频或人工重置；不会自动重跑。",
    FAILED: "音频准备失败，请重新提交要求再试一次。", CANCELLED: "任务已取消。",
    LEGACY_READ_ONLY: "旧队列只读：可播放已生成音频，不会按旧快照自动续跑。",
  };
  const detail = Array.isArray(queue.errors) ? queue.errors.filter(v => typeof v === "string").at(-1) : "";
  return (messages[queue.status] || "正在查询任务状态…") + (detail ? " " + detail : "");
}
export function segmentConfirmation(segment, reason = "", reset = false) {
  if (segment?.status !== "NEEDS_REVIEW" || !/^[0-9a-f]{64}$/.test(segment.script_hash || "")
      || !/^[0-9a-f-]{36}$/i.test(segment.audio_id || "")) throw new Error("请重新加载待核查片段");
  const body = { confirmed: true, expected_script_hash: segment.script_hash, expected_audio_id: segment.audio_id };
  if (reset) {
    if (reason.trim().length < 10 || reason.trim().length > 500) throw new Error("人工重置理由须为10至500字");
    body.reason = reason.trim();
  }
  return body;
}
// 网络失败时保留原payload/key；明确接收到队列后清空，下一次新任务另用新key。
export function audioSubmission(body, previous, uuid = () => crypto.randomUUID()) {
  const fingerprint = JSON.stringify(body);
  if (previous?.fingerprint === fingerprint) return previous;
  return { fingerprint, body: { ...body, request_id: uuid() } };
}
