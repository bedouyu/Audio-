<script setup>
import { computed, ref, shallowRef, watch } from "vue";
import { useScriptNotebook } from "../composables/useScriptNotebook.js";
import ScriptDiff from "./ScriptDiff.vue";

const props = defineProps({ segments: Array, busy: Boolean, queueId: String, ownerId: [Number, String], drafts: Object,
  save: Function, revisions: Array, acceptRevision: Function, rejectRevision: Function, revertRevision: Function, requestError: String,
  voices: Array, currentVoice: String });
const { page, draft, saved, current, list, dirty, conflicted, saving, error, flush, discard, turn } = useScriptNotebook(props);
const action = shallowRef(null), actionError = ref(""), actionMessage = ref("");
const selectedVoice = ref("__keep_current__");
const pending = computed(() => (props.revisions || []).filter(revision => revision.status === "PENDING"));
const recent = computed(() => (props.revisions || []).filter(revision => revision.status !== "PENDING").slice(0, 5));
const statusLabel = computed(() => {
  switch (current.value?.status) {
    case "READY": return "已生成音频";
    case "SYNTHESIZING": return "正在合成";
    case "NEEDS_REVIEW": return "合成结果待核验";
    case "PENDING": return "等待合成";
    default: return "";
  }
});
const locked = computed(() => props.busy || saving.value || !!action.value);
const revisionLabel = revision => revision.segment_number != null ? `第 ${revision.segment_number} 段修改` : "整篇讲稿修改";
const reviewStatus = revision => ({ ACCEPTED: "已确认", REJECTED: "已保留原稿", STALE: "原稿已更新，请重新修改", UNCHANGED: "正文未变" })[revision.status] || revision.status;
watch(() => [props.queueId, props.ownerId], () => { action.value = null; actionError.value = ""; actionMessage.value = ""; selectedVoice.value = "__keep_current__"; }, { flush: "sync" });
async function review(revision, operation) {
  if (locked.value) return;
  const callback = operation === "accept" ? props.acceptRevision : operation === "reject" ? props.rejectRevision : props.revertRevision;
  if (!callback) { actionError.value = "当前操作暂不可用，请刷新后重试。"; return; }
  const request = { id: revision.id, operation, queueId: props.queueId, ownerId: props.ownerId };
  action.value = request;
  actionError.value = "";
  actionMessage.value = "";
  try {
    const success = operation === "accept" && selectedVoice.value !== "__keep_current__"
      ? await callback(revision.id, selectedVoice.value) : await callback(revision.id);
    if (action.value !== request) return;
    if (success !== true) actionError.value = "操作未完成，讲稿仍保留，请刷新后重试。";
    else actionMessage.value = operation === "revert" ? "已创建撤回版本，请在待确认修改中审阅。"
      : operation === "accept" ? "已确认修改，正在更新音频。" : "已保留原稿。";
  } catch {
    if (action.value === request) actionError.value = "操作失败，讲稿仍保留，请稍后重试。";
  } finally {
    if (action.value === request) action.value = null;
  }
}
</script>

<template>
  <section v-if="list.length || pending.length" class="script-notebook">
    <div v-if="pending.length" class="revision-list" aria-label="待确认讲稿修改">
      <article v-for="revision in pending" :key="revision.id" class="revision-card">
        <div class="row spaced"><h3>{{ revisionLabel(revision) }}</h3><span class="review-badge">待你确认</span></div>
        <p v-if="revision.instruction" class="revision-instruction">{{ revision.instruction }}</p>
        <p class="review-help">绿色为新增，红色删除线为删除。确认后才替换讲稿并更新音频。</p>
        <ScriptDiff :before="revision.before_text" :after="revision.after_text" />
        <label v-if="voices?.length" class="review-help">确认后的音色
          <select v-model="selectedVoice" :disabled="locked" aria-label="修改确认音色">
            <option value="__keep_current__">沿用当前音色{{ currentVoice ? ' · ' + currentVoice : '' }}</option>
            <option value="">按新讲稿语言自动选择</option>
            <option v-for="voice in voices" :key="voice.name" :value="voice.name">{{ voice.display_name || voice.name }}</option>
          </select>
        </label>
        <div class="row review-actions">
          <button class="primary" :disabled="locked" @click="review(revision, 'accept')">{{ action?.id === revision.id && action.operation === 'accept' ? '正在确认…' : '确认修改' }}</button>
          <button :disabled="locked" @click="review(revision, 'reject')">保留原稿</button>
        </div>
      </article>
    </div>
    <p v-if="actionError" class="review-error" role="alert">{{ requestError || actionError }}</p>
    <p v-if="actionMessage" class="review-help" role="status">{{ actionMessage }}</p>
    <template v-if="list.length">
      <div class="notebook-bar">
        <div class="row spaced">
          <h3>当前生效讲稿</h3>
          <span class="muted">
            第 {{ page + 1 }} / {{ list.length }} 段 · {{ (current?.script || "").length }} 字<template v-if="statusLabel"> · {{ statusLabel }}</template>
            <template v-if="saving"> · 正在提交审阅…</template>
            <template v-else-if="dirty"> · 本地修改待提交</template>
            <template v-else-if="busy"> · 正在更新…</template>
          </span>
        </div>
        <p v-if="error" class="review-error" role="alert">{{ error }}</p>
        <div class="row">
          <button :disabled="saving || page === 0" @click="turn(-1)">上一页</button>
          <button :disabled="saving || page >= list.length - 1" @click="turn(1)">下一页</button>
          <small class="muted">改稿后先审阅，再确认；换页和离开不会自动应用修改</small>
        </div>
      </div>
      <div class="notebook-page">
        <textarea v-model="draft" spellcheck="false" :readonly="locked" aria-label="讲稿正文" />
      </div>
      <article v-if="dirty" class="revision-card local-review">
        <div class="row spaced"><h3>本地修改预览</h3><span class="review-badge">尚未提交</span></div>
        <p v-if="conflicted" class="review-error" role="status">原稿已在其他位置更新，当前输入已保留。请先复制要保留的文字，再放弃本地修改以载入最新稿，核对后重新编辑。</p>
        <ScriptDiff :before="saved" :after="draft" />
        <div class="row review-actions">
          <button class="primary" :disabled="locked || conflicted" @click="flush">{{ saving ? '正在提交…' : '提交审阅' }}</button>
          <button :disabled="locked" @click="discard">放弃本地修改</button>
          <small class="muted">提交后仍需在上方确认，才会替换原稿</small>
        </div>
      </article>
    </template>
    <details v-if="recent.length" class="revision-history">
      <summary>最近修改记录 · {{ recent.length }} 条</summary>
      <article v-for="revision in recent" :key="revision.id" class="revision-card">
        <div class="row spaced"><h3>{{ revisionLabel(revision) }}</h3><span class="muted">{{ reviewStatus(revision) }}</span></div>
        <details><summary>查看修改对比</summary><ScriptDiff :before="revision.before_text" :after="revision.after_text" /></details>
        <div v-if="revision.status === 'ACCEPTED' && revertRevision" class="row review-actions">
          <button :disabled="locked" @click="review(revision, 'revert')">撤回此修改</button>
          <small class="muted">会创建反向修改，审阅确认后再恢复原稿</small>
        </div>
      </article>
    </details>
  </section>
</template>

<style scoped>
.script-notebook, .revision-list { display: grid; gap: 12px; }
.notebook-bar h3, .revision-card h3 { margin: 0; font-size: 15px; }
.revision-card { display: grid; gap: 10px; padding: 14px; border: 1px solid #cdded7; border-radius: 12px; background: #f8fbf9; }
.local-review { background: #fffcf3; border-color: #e5d6a3; }
.review-badge { border-radius: 20px; background: #e8f1eb; color: #2f6749; padding: 3px 9px; font-size: 12px; white-space: nowrap; }
.review-help, .revision-instruction, .review-error { margin: 0; font-size: 13px; line-height: 1.7; }
.review-help { color: #667085; }
.revision-instruction { color: #344054; white-space: pre-wrap; overflow-wrap: anywhere; }
.review-error { color: #a62932; }
.review-actions { flex-wrap: wrap; }
.revision-history { display: grid; gap: 10px; color: #667085; font-size: 13px; }
.revision-history > .revision-card { margin-top: 10px; }
summary { cursor: pointer; padding: 6px 0; }
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
