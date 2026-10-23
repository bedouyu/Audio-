<script setup>
import { computed, ref, watch } from "vue";

const props = defineProps({ segments: Array, busy: Boolean });
const emit = defineEmits(["save"]);

const page = ref(0);
const draft = ref("");
const saved = ref("");

const list = computed(() => props.segments || []);
const current = computed(() => list.value[page.value] || null);
const dirty = computed(() => draft.value.trim() !== saved.value.trim());
const statusLabel = computed(() => {
  switch (current.value?.status) {
    case "READY": return "已生成音频";
    case "SYNTHESIZING": return "正在合成";
    case "NEEDS_REVIEW": return "合成结果待核验";
    case "PENDING": return "等待合成";
    default: return "";
  }
});

watch(list, (values) => { if (page.value >= values.length) page.value = 0; }, { immediate: true });
watch(current, (segment) => {
  saved.value = segment?.script || "";
  draft.value = saved.value;
}, { immediate: true });

// 离开这一页（切页、失焦）时保存，不需要"保存"按钮。
function flush() {
  if (!current.value || !dirty.value || !draft.value.trim()) return;
  saved.value = draft.value;
  emit("save", { number: current.value.segment_number, text: draft.value.trim() });
}
function turn(delta) {
  const next = page.value + delta;
  if (next < 0 || next >= list.value.length) return;
  flush();
  page.value = next;
}
</script>
<template>
  <section v-if="list.length" class="script-notebook">
    <div class="notebook-bar">
      <div class="row spaced">
        <h3>讲稿</h3>
        <span class="muted">
          第 {{ page + 1 }} / {{ list.length }} 段 · {{ (current?.script || "").length }} 字<template v-if="statusLabel"> · {{ statusLabel }}</template>
          <template v-if="dirty"> · 未保存</template>
          <template v-else-if="busy"> · 正在重念…</template>
        </span>
      </div>
      <div class="row">
        <button :disabled="page === 0" @click="turn(-1)">上一页</button>
        <button :disabled="page >= list.length - 1" @click="turn(1)">下一页</button>
        <small class="muted">直接在稿纸上改，换页或点别处就会保存并重念这一段</small>
      </div>
    </div>
    <div class="notebook-page">
      <textarea v-model="draft" spellcheck="false" :readonly="busy"
        aria-label="讲稿正文" @blur="flush" />
    </div>
  </section>
</template>
<style scoped>
.script-notebook { display: grid; gap: 10px; }
.notebook-bar h3 { margin: 0; font-size: 15px; }
.notebook-page {
  background-color: #fffbe6;
  background-image:
    linear-gradient(90deg, transparent 54px, #f0b3b3 54px 55px, transparent 55px),
    repeating-linear-gradient(#fffbe6 0 29px, #ecdfae 29px 30px);
  border: 1px solid #e3d59c;
  border-radius: 12px;
  box-shadow: 0 10px 26px #6b5a1f14;
  padding: 10px 16px 10px 68px;
}
.notebook-page textarea {
  width: 100%;
  min-height: 300px;
  max-height: 60vh;
  resize: vertical;
  border: 0;
  outline: none;
  background: transparent;
  line-height: 30px;
  font-size: 15px;
  color: #3b3524;
  font-family: "Segoe UI", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
}
</style>
