<script setup>
import { computed } from "vue";
import { runtimeSummary } from "../../../shared/runtime-trace.js";
const props = defineProps({ events: Array });
const summary = computed(() => runtimeSummary(props.events));
</script>
<template>
  <div class="card">
    <h3>本次轨迹概览</h3>
    <p>Agent调用实例 {{ summary.invocations }} · 模型开始/完成 {{ summary.models }}/{{ summary.completedModels }} · 工具调用 {{ summary.tools }}</p>
    <p>工具失败 {{ summary.failedTools }} · 缺少结束记录 {{ summary.pendingTools }}</p>
    <p class="muted">供应商用量报告 {{ summary.usageReports }} 次 · 已报告总token {{ summary.usageReports ? summary.actualTokens : '暂无' }}（不包含没有报告的请求）</p>
    <details><summary>按调用编号查看工具状态</summary>
      <div v-for="call in summary.calls" :key="call.call">
        <p>{{ call.tool }} · {{ call.status }} · {{ call.elapsed ?? '—' }} ms · {{ call.error }}</p>
        <small>call {{ call.call }} · invocation {{ call.invocation }} · parent {{ call.parent || '根调用' }}</small>
      </div>
    </details>
  </div>
</template>
