// 工具观察属于外部数据。只识别固定结构，不执行内容中的脚本或HTML。
export function retrievalDecision(event) {
  const step = event?.payload || event;
  if (step?.tool !== "knowledge_search" || step?.kind !== "tool_end") return null;
  try {
    const result = typeof step.observation === "string"
      ? JSON.parse(step.observation) : step.observation;
    if (!result || typeof result.actual_retrieval !== "string") return null;
    return {
      requested: `${result.requested_retrieval} / ${result.requested_context}`,
      actual: `${result.actual_retrieval} / ${result.actual_context}`,
      reason: typeof result.strategy_reason === "string" ? result.strategy_reason : "未说明",
      hits: Number.isFinite(result.hit_count) ? result.hit_count : 0,
      tokens: Number.isFinite(result.context_tokens_bge) ? result.context_tokens_bge : 0,
      cache: typeof result.bm25_index_cache === "string" ? result.bm25_index_cache : "未提供",
      revision: typeof result.index_revision === "string" ? result.index_revision : "",
      warnings: Array.isArray(result.warnings) ? result.warnings.filter(item => typeof item === "string") : [],
    };
  } catch { return null; }
}
