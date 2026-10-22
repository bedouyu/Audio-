<script setup>
import { computed } from "vue";
import { memoryDecision } from "../../../shared/memory-trace.js";
const props = defineProps({ event: Object });
const decision = computed(() => memoryDecision(props.event));
</script>
<template>
  <div v-if="decision" class="card">
    <strong>上下文：{{ decision.label }}</strong>
    <p class="muted">{{ decision.characters }} / {{ decision.budget }} 字符 · 扫描 {{ decision.scanned }} 条历史 · {{ decision.fragments.length }} 个原文片段</p>
    <small>{{ decision.checkpointLoaded ? '已加载并核对会话检查点' : '本轮依据原始历史建立上下文' }}；摘记不是模型摘要或事实认证。</small>
    <p v-for="warning in decision.warnings" :key="warning" class="danger">{{ warning }}</p>
    <details><summary>本轮实际使用的历史片段（含来源与位置）</summary><pre>{{ JSON.stringify(decision.fragments, null, 2) }}</pre></details>
  </div>
</template>
