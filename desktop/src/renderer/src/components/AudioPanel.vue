<script setup>
import { computed, ref } from "vue";
import { Headphones, Play } from "lucide-vue-next";
import { canRefill } from "../../../shared/audio-queue-policy.js";
import AudioRequestComposer from "./AudioRequestComposer.vue";
import AudioHistory from "./AudioHistory.vue";
import ReasonDialog from "./ReasonDialog.vue";
const props = defineProps({ workspace: Object, audio: Object });
const emit = defineEmits(["submit"]);
const { state, safe, refreshProject } = props.workspace;
const { state: playback, current, message } = props.audio;
const resetTarget = ref(null);
const active = computed(() => playback.queue && playback.project?.id === state.project?.id);
const records = computed(() => state.records.filter(item => item.record_type === "lesson" && item.metadata?.audio_url));
const history = computed(() => playback.history.filter(item => item.project_id === state.project?.id));
const sources = computed(() => {
  const citations = current.value?.citations || playback.queue?.citations;
  return Array.isArray(citations) ? citations : [];
});
function openSource(source) { if (/^https?:\/\//.test(source || "")) window.audioAgent.openSource(source); }
async function recover(segment) {
  if (!confirm("核验这段已有音频并恢复？不会再次调用语音合成。")) return;
  await safe(async () => { await props.audio.reviewSegment(segment); await refreshProject(); });
}
async function reset(reason) {
  const segment = resetTarget.value; resetTarget.value = null;
  if (segment) await safe(() => props.audio.reviewSegment(segment, reason));
}
</script>
<template>
  <section class="panel audio-center-panel">
    <ReasonDialog :open="!!resetTarget" title="重置音频片段" message="旧音频和操作记录会保留。重置后请点击继续生成，才会发起新的合成尝试。"
      @cancel="resetTarget = null" @confirm="reset" />
    <div class="panel-heading"><div><div class="eyebrow">LISTEN & EXPLORE</div><h2>{{ state.project.title }}</h2></div><Headphones :size="26" class="accent" /></div>
    <p class="muted">继续说出你的要求，会结合这个空间的资料准备内容。</p>
    <AudioRequestComposer :audio="audio" compact @submit="emit('submit', $event)" />
    <div v-if="active" class="card current-program">
      <div class="row spaced"><h3>{{ current?.title || (playback.queue.status === "FAILED" ? "音频准备失败" : "正在准备你的音频") }}</h3><span class="badge">{{ playback.queue.lessons?.length || 0 }} 段已就绪</span></div>
      <p>{{ message }}</p>
      <p v-if="playback.instruction" class="muted">你的要求：{{ playback.instruction }}</p>
      <p class="muted">底部播放器可连续播放，返回首页也能继续收听。</p>
      <button v-if="canRefill(playback.queue, true)" :disabled="playback.refilling" @click="audio.refill(true)">{{ playback.refilling ? "正在生成…" : "继续生成后续内容" }}</button>
      <details v-if="current?.script"><summary>查看当前文字稿</summary><pre>{{ current.script }}</pre></details>
      <details v-if="sources.length"><summary>参考来源 · {{ sources.length }}</summary>
        <div v-for="(item, index) in sources" :key="index" class="content-source">
          <button v-if="/^https?:\/\//.test(item.source || item.url || '')" @click="openSource(item.source || item.url)">{{ item.title || item.source || item.url }}</button>
          <span v-else>{{ item.title || item.source || item }}</span>
          <small v-if="item.reverified === false" class="muted">沿用原音频来源，未重新检索</small>
        </div>
      </details>
      <details v-if="playback.queue.segments?.length"><summary>分段与任务详情</summary>
        <article v-for="segment in playback.queue.segments" :key="segment.segment_number" class="segment-detail">
          <p>第 {{ segment.segment_number }} 段 · {{ segment.status }}</p>
          <div v-if="segment.status === 'NEEDS_REVIEW'" class="row">
            <button :disabled="state.busy" @click="recover(segment)">核验原音频并恢复</button><button :disabled="state.busy" @click="resetTarget = segment">重置尝试</button>
          </div>
          <details v-if="segment.audit?.length"><summary>操作记录</summary><pre>{{ JSON.stringify(segment.audit, null, 2) }}</pre></details>
        </article>
        <small>任务 {{ playback.queue.queue_id }} · {{ playback.queue.status }}</small>
      </details>
    </div>
    <div v-if="history.length" class="card"><h3>收听记录</h3>
      <AudioHistory :queues="history" :current-id="playback.queue?.queue_id" :busy="playback.restoring" @play="audio.openQueue($event, state.project)" />
    </div>
    <div class="card"><h3>这个空间的音频</h3>
      <div v-for="record in records" :key="record.id" class="record-audio">
        <div class="row spaced"><p>{{ record.title }}</p><button @click="audio.playRecord(record, state.project)"><Play :size="14" /> 收听</button></div>
        <details><summary>文字稿</summary><pre>{{ record.content }}</pre></details>
      </div>
      <p v-if="!records.length" class="muted">准备好的音频会保存在这里。也可以先打开资料，导入你想听的内容。</p>
    </div>
  </section>
</template>
