const finite = value => typeof value === "number" && Number.isFinite(value) && value >= 0;
export function orderedTrace(events) {
  const seen = new Set();
  const unique = (Array.isArray(events) ? events : []).filter((event, index) => {
    const step = event?.payload || event;
    const key = step?.event_id ? `event:${step.event_id}` : event?.id ? `db:${event.id}` : `legacy:${index}`;
    if (seen.has(key)) return false; seen.add(key); return true;
  });
  const groups = new Map();
  for (const event of unique) {
    const step = event?.payload || event;
    if (!step?.run_id || !finite(step.sequence)) continue;
    if (!groups.has(step.run_id)) groups.set(step.run_id, []);
    groups.get(step.run_id).push(event);
  }
  for (const values of groups.values()) values.sort((a,b) => (a.payload || a).sequence - (b.payload || b).sequence);
  // 在每个run自己的位置内排序，避免跨run比较器不传递造成乱序。
  return unique.map(event => {
    const step = event?.payload || event;
    return step?.run_id && finite(step.sequence) ? groups.get(step.run_id).shift() : event;
  });
}
export function runtimeSummary(events) {
  const steps = orderedTrace(events).map(e => e?.payload || e);
  const invocations = new Set(), calls = new Map();
  let models = 0, completedModels = 0, usageReports = 0, actualTokens = 0;
  for (const step of steps) {
    if (step?.invocation_id) invocations.add(step.invocation_id);
    if (step?.kind === "model_start") models++;
    if (step?.kind === "model_end") {
      completedModels++;
      if (step.usage_source === "provider_response" && finite(step.usage?.total_tokens)) {
        usageReports++; actualTokens += step.usage.total_tokens;
      }
    }
    if (step?.call_id && ["tool_start", "tool_end"].includes(step.kind)) {
      const previous = calls.get(step.call_id) || {};
      calls.set(step.call_id, { ...previous, tool: step.tool, call: step.call_id, parent: step.parent_call_id,
        invocation: step.invocation_id, status: step.kind === "tool_end" ? step.status : previous.status || "RUNNING",
        elapsed: finite(step.elapsed_ms) ? step.elapsed_ms : previous.elapsed,
        error: step.error_code || previous.error || "" });
    }
  }
  const values = [...calls.values()];
  return { models, completedModels, invocations: invocations.size, tools: values.length,
    failedTools: values.filter(c => !["RUNNING", "COMPLETED"].includes(c.status)).length,
    pendingTools: values.filter(c => c.status === "RUNNING").length,
    usageReports, actualTokens, calls: values };
}
export function modelBudgetDecision(event) {
  const step = event?.payload || event;
  if (!["context_budget", "context_compaction", "model_end"].includes(step?.kind)) return null;
  const budget = step.budget;
  if (!budget || !finite(budget.context_window) || !finite(budget.total_reserved)) return null;
  return { budget, usage: step.usage_source === "provider_response" ? step.usage : null };
}
