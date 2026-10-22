<script setup>
import { computed } from "vue";
import { retrievalDecision } from "../../../shared/retrieval-trace.js";
const props = defineProps({ event: Object });
const decision = computed(() => retrievalDecision(props.event));
</script>
<template>
  <div v-if="decision" class="card">
    <strong>检索策略</strong>
    <p class="muted">模型请求：{{ decision.requested }}</p>
    <p>实际执行：{{ decision.actual }}</p>
    <p class="muted">选择依据：{{ decision.reason }}</p>
    <small>{{ decision.hits }} 段证据 · {{ decision.tokens }} BGE tokens（非回答模型精确计数）</small>
    <p class="muted">BM25索引缓存：{{ decision.cache }} · {{ decision.revision?.slice(0, 12) }}</p>
    <p v-for="warning in decision.warnings" :key="warning" class="danger">{{ warning }}</p>
  </div>
</template>
