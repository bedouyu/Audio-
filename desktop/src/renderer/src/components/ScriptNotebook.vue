<script setup>
import { computed, ref, watch } from "vue";

const props = defineProps({ segments: Array, busy: Boolean });
const emit = defineEmits(["save", "undo", "rewrite", "extend"]);

const page = ref(0);
const draft = ref("");
const whole = ref("");

const list = computed(() => props.segments || []);
const current = computed(() => list.value[page.value] || null);
const dirty = computed(() => (current.value?.script || "").trim() !== draft.value.trim());
const statusLabel = computed(() => {
  switch (current.value?.status) {
    case "READY": return "已生成音频";
    case "SYNTHESIZING": return "正在合成";
    case "NEEDS_REVIEW": return "合成结果待核验";
    case "PENDING": return "等待合成";
    default: return "";
  }
});
const canUndo = computed(() => (current.value?.audit || []).some((entry) => entry.action === "REVISE_SCRIPT"));

watch(list, (values) => { if (page.value >= values.length) page.value = 0; }, { immediate: true });
watch(current, (segment) => { draft.value = segment?.script || ""; }, { immediate: true });

function previousPage() { if (page.value > 0) page.value -= 1; }
function nextPage() { if (page.value < list.value.length - 1) page.value += 1; }
function rewrite() {
  const text = whole.value.trim();
  if (!text) return;
  emit("rewrite", text);
  whole.value = "";
}
function extend() {
  const text = whole.value.trim();
  if (!text) return;
  emit("extend", text);
  whole.value = "";
}
function save() {
  if (!current.value || !dirty.value || !draft.value.trim()) return;
  emit("save", { number: current.value.segment_number, text: draft.value.trim() });
}
</script>
<template>
  <section v-if="list.length" class="script-notebook">
    <div class="notebook-bar">
      <div class="row spaced">
        <h3>讲稿</h3>
        <span class="muted">
          第 {{ page + 1 }} / {{ list.length }} 段 · {{ (current?.script || "").length }} 字<template v-if="statusLabel"> · {{ statusLabel }}</template>
        </span>
      </div>
      <div class="row">
        <button :disabled="page === 0" @click="previousPage">上一页</button>
        <button :disabled="page >= list.length - 1" @click="nextPage">下一页</button>
        <button :disabled="!dirty || busy" @click="save">{{ busy ? "正在重念…" : "保存并重念这段" }}</button>
        <button v-if="canUndo" :disabled="busy" @click="emit('undo', current.segment_number)">撤回上一版</button>
        <small v-if="dirty" class="muted">有未保存的修改</small>
      </div>
    </div>
    <div class="notebook-page">
      <textarea v-model="draft" spellcheck="false" :readonly="busy" aria-label="讲稿正文" />
    </div>
    <form class="whole-rewrite" @submit.prevent="rewrite">
      <input v-model="whole" :disabled="busy" placeholder="整篇一起改：例如「去掉开场白和串场，只保留英语-汉语-英语两遍-下一句」" />
      <button type="submit" :disabled="busy || !whole.trim()">{{ busy ? "正在处理…" : "整篇重写" }}</button>
      <button type="button" :disabled="busy || !whole.trim()" @click="extend">追加一些</button>
    </form>
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
.whole-rewrite { display: flex; gap: 8px; }
.whole-rewrite input { flex: 1; min-height: 36px; }
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
