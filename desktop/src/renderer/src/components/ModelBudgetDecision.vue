<script setup>
import { computed } from "vue";
import { modelBudgetDecision } from "../../../shared/runtime-trace.js";
const props = defineProps({ event: Object });
const decision = computed(() => modelBudgetDecision(props.event));
</script>
<template>
  <div v-if="decision" class="card">
    <strong>完整模型请求预算（估算）</strong>
    <p>{{ decision.budget.model }} · {{ decision.budget.counting_method }}</p>
    <p :class="{ danger: !decision.budget.within_budget }">总预留 {{ decision.budget.total_reserved }} / 上限 {{ decision.budget.context_window }}</p>
    <small>完整输入 {{ decision.budget.input_tokens }} · 输出预留 {{ decision.budget.output_reserve }} · 安全余量 {{ decision.budget.safety_margin }}</small>
    <p class="muted">包含工具定义和协议预留；估算不等于供应商实际计费token。</p>
    <p v-if="decision.usage">供应商报告：输入 {{ decision.usage.prompt_tokens }} · 输出 {{ decision.usage.completion_tokens }} · 总计 {{ decision.usage.total_tokens }}</p>
  </div>
</template>
