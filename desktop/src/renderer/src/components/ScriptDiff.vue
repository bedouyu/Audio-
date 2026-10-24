<script setup>
import { computed } from "vue";
import { scriptDiff } from "../../../shared/script-diff.js";

const props = defineProps({ before: { type: String, default: "" }, after: { type: String, default: "" } });
const parts = computed(() => scriptDiff(props.before, props.after));
const counts = computed(() => parts.value.reduce((result, part) => {
  if (part.type !== "equal") result[part.type] += Array.from(part.text).length;
  return result;
}, { add: 0, remove: 0 }));
</script>

<template>
  <div class="script-diff">
    <div class="diff-legend" aria-label="修改标记说明">
      <span class="addition">新增 {{ counts.add }} 字符</span>
      <span class="deletion">删除 {{ counts.remove }} 字符</span>
      <span v-if="!counts.add && !counts.remove">正文无变化</span>
    </div>
    <div class="diff-text" role="document" aria-label="讲稿修改对比"><template v-for="(part, index) in parts" :key="index"><ins v-if="part.type === 'add'">{{ part.text }}</ins><del v-else-if="part.type === 'remove'">{{ part.text }}</del><span v-else>{{ part.text }}</span></template></div>
  </div>
</template>

<style scoped>
.script-diff { display: grid; gap: 8px; }
.diff-legend { display: flex; flex-wrap: wrap; gap: 8px 14px; font-size: 12px; color: #667085; }
.addition, ins { color: #126a39; background: #e5f5e9; }
.deletion, del { color: #a62932; background: #fde8e9; }
.diff-legend .addition, .diff-legend .deletion { padding: 2px 6px; border-radius: 4px; }
.diff-text { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.9; max-height: 48vh; overflow-y: auto; padding: 14px; background: #fff; border: 1px solid #e0e5e9; border-radius: 8px; }
ins { text-decoration: none; border-bottom: 1px solid #61b17e; }
del { text-decoration: line-through; }
</style>
