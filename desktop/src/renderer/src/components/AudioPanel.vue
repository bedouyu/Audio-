<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { Headphones, Play } from "lucide-vue-next";
import AudioRequestComposer from "./AudioRequestComposer.vue";
import AudioHistory from "./AudioHistory.vue";
import ReasonDialog from "./ReasonDialog.vue";
import ScriptNotebook from "./ScriptNotebook.vue";
const props = defineProps({ workspace: Object, audio: Object });
const emit = defineEmits(["submit"]);
const { state, safe, refreshProject } = props.workspace;
const { state: playback, current, message } = props.audio;
const resetTarget = ref(null);
const scriptBusy = ref(false);
// 后端可能重启过，登录时读到的音色列表会变旧；进入音频页时再刷一次。
onMounted(() => props.audio.refreshVoices?.());
const active = computed(() => playback.queue && playback.project?.id === state.project?.id);
const records = computed(() => state.records.filter(item => item.record_type === "lesson" && item.metadata?.audio_url));
const history = computed(() => playback.history.filter(item => item.project_id === state.project?.id));
const sources = computed(() => {
  const citations = current.value?.citations || playback.queue?.citations;
  return Array.isArray(citations) ? citations : [];
});
function openSource(source) { if (/^https?:\/\//.test(source || "")) window.audioAgent.openSource(source); }
// 讲稿常驻右侧；修改先审阅差异，明确确认后才写回并重念。
let idleTimer = null;
const panel = ref(null);
onMounted(() => {
  // v-show 隐藏的面板仍会挂载；只有当前空间的稿纸实际可见时才做额外对齐。
  idleTimer = setInterval(() => {
    if (active.value && document.visibilityState === "visible" && panel.value?.getClientRects().length)
      props.audio.refresh();
  }, 10000);
});
onBeforeUnmount(() => { if (idleTimer) clearInterval(idleTimer); });
const scriptSegments = computed(() => active.value ? (playback.queue?.segments || []).filter(segment => segment.script) : []);
function saveNotebook(payload) {
  return props.audio.reviseSegment(payload.number, { text: payload.text }, payload.queueId, payload.expectedScriptHash);
}
function acceptRevision(id, voice) { return props.audio.acceptScriptRevision(id, playback.queue?.queue_id, voice); }
function rejectRevision(id) { return props.audio.rejectScriptRevision(id, playback.queue?.queue_id); }
function revertRevision(id) { return props.audio.revertScriptRevision(id, playback.queue?.queue_id); }
async function recover(segment) {
  if (!confirm("核验这段已有音频并恢复？不会再次调用语音合成。")) return;
  await safe(async () => { await props.audio.reviewSegment(segment); await refreshProject(); });
}
async function reset(reason) {
  const segment = resetTarget.value; resetTarget.value = null;
  if (segment) await safe(() => props.audio.reviewSegment(segment, reason));
}
async function exportFullScript() {
  scriptBusy.value = true;
  try {
    const info = await props.audio.exportScript();
    if (info) state.notice = "讲稿已导出为单个文件：" + (info.title || "音频讲稿");
  } catch (error) { state.notice = error.message; }
  finally { scriptBusy.value = false; }
}
async function exportRecord(record) {
  try {
    const saved = await window.audioAgent.saveScript({
      filename: (record.title || "音频讲稿").replace(/[\\/:*?"<>|]/g, "_").slice(0, 60) + ".md",
      content: "# " + (record.title || "音频讲稿") + "\n\n" + record.content + "\n",
    });
    if (saved) state.notice = "文字稿已导出：" + (record.title || "");
  } catch (error) { state.notice = error.message; }
}
</script>
<template>
  <section ref="panel" class="panel audio-center-panel">
    <ReasonDialog :open="!!resetTarget" title="重置音频片段" message="旧音频和操作记录会保留。重置后请点击继续生成，才会发起新的合成尝试。"
      @cancel="resetTarget = null" @confirm="reset" />
    <div class="panel-heading"><div><div class="eyebrow">LISTEN & EXPLORE</div><h2>{{ state.project.title }}</h2></div><Headphones :size="26" class="accent" /></div>
    <p class="muted">
      <template v-if="scriptSegments.length">可以直接编辑讲稿，也可以在左边说出修改要求。新增标绿、删除标红，确认后才应用；选择保留原稿即可放弃修改。</template>
      <template v-else>说一句想听什么，会结合这个空间的资料准备内容。</template>
    </p>
    <template v-if="scriptSegments.length">
      <ScriptNotebook :key="state.user?.id + ':' + playback.queue?.queue_id" :segments="scriptSegments"
        :queue-id="playback.queue?.queue_id" :owner-id="state.user?.id"
        :drafts="playback.scriptDrafts" :busy="playback.revising != null || playback.revoicing" :save="saveNotebook"
        :revisions="playback.queue?.script_revisions || []" :accept-revision="acceptRevision"
        :reject-revision="rejectRevision" :revert-revision="revertRevision" :request-error="playback.error"
        :voices="playback.voices" :current-voice="playback.queue?.voice" />
      <details class="new-audio-entry">
        <summary>＋ 制作一条新音频（换主题或重新生成）</summary>
        <AudioRequestComposer :audio="audio" compact @submit="emit('submit', $event)" />
      </details>
    </template>
    <AudioRequestComposer v-else :audio="audio" compact @submit="emit('submit', $event)" />
    <div v-if="active" class="card current-program">
      <div class="row spaced"><h3>{{ current?.title || (playback.queue.status === "FAILED" ? "音频准备失败" : "正在准备你的音频") }}</h3><span class="badge">{{ playback.queue.lessons?.length || 0 }} 段已就绪</span></div>
      <p>{{ message }}</p>
      <p v-if="playback.instruction" class="muted">你的要求：{{ playback.instruction }}</p>
      <p class="muted">底部播放器可连续播放，返回首页也能继续收听。</p>
      <p v-if="scriptSegments.length" class="muted">讲稿不变；换音色直接在左边说「换成我的声音」或「换成官方示例音色」。</p>
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
    <div v-if="active" class="card">
      <div class="row spaced"><h3>完整讲稿</h3><span class="badge">{{ scriptSegments.length }} 段</span></div>
      <p class="muted">把各段正文合并成一份，导出后是一个文件，可直接保存或继续修改。</p>
      <div class="row">
        <button :disabled="scriptBusy" @click="exportFullScript">导出为文件</button>
      </div>
    </div>
    <div v-if="history.length" class="card"><h3>收听记录</h3>
      <AudioHistory :queues="history" :current-id="playback.queue?.queue_id" :busy="playback.restoring" @play="audio.openQueue($event, state.project)" />
    </div>
    <div class="card"><h3>这个空间的音频</h3>
      <div v-for="record in records" :key="record.id" class="record-audio">
        <div class="row spaced"><p>{{ record.title }}</p>
          <div class="row">
            <button @click="audio.playRecord(record, state.project)"><Play :size="14" /> 收听</button>
            <button title="把这个文字稿导出为文件" @click="exportRecord(record)">导出</button>
          </div>
        </div>
        <details><summary>文字稿</summary><pre>{{ record.content }}</pre></details>
      </div>
      <p v-if="!records.length" class="muted">准备好的音频会保存在这里。也可以先打开资料，导入你想听的内容。</p>
    </div>
  </section>
</template>
