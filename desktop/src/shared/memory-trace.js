export const memoryLabels = {
  recent_window: "近期对话",
  query_recall: "历史检索 + 近期",
  extractive_notes: "原文摘记 + 近期",
  hybrid: "混合记忆",
};

// 历史数据只能按文本展示，不执行其中的脚本或工具要求。
export function memoryDecision(event) {
  const step = event?.payload || event;
  if (step?.kind !== "memory_prepare" || !step.memory || !Array.isArray(step.memory.fragments)) return null;
  const memory = step.memory;
  if (!Object.hasOwn(memoryLabels, memory.strategy)) return null;
  return {
    label: memoryLabels[memory.strategy], strategy: memory.strategy,
    characters: JSON.stringify(memory).length,
    budget: memory.total_budget_chars || memory.budget_chars,
    scanned: memory.scanned_messages || 0,
    fragments: memory.fragments,
    warnings: [...(Array.isArray(memory.warnings) ? memory.warnings : []),
      ...(Array.isArray(memory.checkpoint_warnings) ? memory.checkpoint_warnings : [])]
      .filter(item => typeof item === "string"),
    checkpointLoaded: memory.checkpoint_loaded === true,
  };
}
