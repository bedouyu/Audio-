<script setup>
import { computed, onMounted, ref } from "vue";
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
const scriptInfo = ref(null);
const scriptOpen = ref(false);
const scriptBusy = ref(false);
const revoiceVoice = ref("");
const contentLanguages = computed(() => {
  const ready = (playback.queue?.lessons || []).map(item => item.script || "").join(" ");
  // 失败的队列还没有就绪片段，用原始要求兜底；标题往往是中文，只在没有要求时使用，
  // 否则会把英文内容的可选项污染成"中英双语"。
  const texts = ready || playback.queue?.instruction || playback.queue?.title || "";
  const languages = [];
  if (/[\u3400-\u9fff]/.test(texts)) languages.push("zh");
  if (/[A-Za-z]/.test(texts)) languages.push("en");
  return languages;
});
const revoiceOptions = computed(() => {
  const voices = playback.voices || [];
  // 判断不出内容语言时不再过滤，交给后端给出准确结论。
  if (!contentLanguages.value.length) return voices;
  return voices.filter((voice) => {
    const supported = Array.isArray(voice.languages) ? voice.languages : [String(voice.locale || "").toLowerCase()];
    return contentLanguages.value.some(language => supported.includes(language));
  });
});
// 只要有脚本片段就显示换音色入口：已完成的直接换，上次失败的也能从这里重念。
// 没有 queue_id 的旧归档记录无法定位队列，直接不显示以免点了没反应。
const canRevoice = computed(() => active.value && !!playback.queue?.queue_id
  && playback.queue?.status !== "CANCELLED"
  && (playback.queue?.total_segments || 0) > 0 && revoiceOptions.value.length > 0);
const revoiceBlocked = computed(() => ["PREPARING", "GENERATING"].includes(playback.queue?.status));
const revoiceStartsOver = computed(() => (playback.queue?.lessons?.length || 0) === 0);
// 后端可能重启过，登录时读到的音色列表会变旧；进入音频页时再刷一次。
onMounted(() => props.audio.refreshVoices?.());
async function confirmRevoice() {
  const voice = revoiceOptions.value.find(item => item.name === revoiceVoice.value);
  if (!voice) return;
  const segments = playback.queue?.lessons?.length || 0;
  if (!confirm(`用「${voice.display_name || voice.name}」重新念这 ${segments} 段？\n\n讲稿和内容不会变，只重新合成语音；按当前时长大约需要几分钟。原音色的音频会保留 8 小时，之后自动清理。`)) return;
  await props.audio.revoice(voice.name);
}
const active = computed(() => playback.queue && playback.project?.id === state.project?.id);
// 逐段改讲稿：先打开某段，再按意见改写或直接保存手工文本；只重合成这一段。
const editing = ref(null);
const editText = ref("");
const editInstruction = ref("");
const scriptExpanded = ref(false);
// 讲稿常驻显示：完成时取正在播的那段；合成中（没有 lessons）取该段最新文字。
const segmentScript = (number) => (playback.queue?.segments || [])
  .find(item => item.segment_number === number)?.script || "";
const currentScript = computed(() => current.value?.script
  || segmentScript((playback.index || 0) + 1)
  || segmentScript(1));
const scriptClamp = computed(() => !scriptExpanded.value && currentScript.value.length > 420);
function openEditor(segment) {
  editing.value = segment.segment_number;
  editText.value = segment.script || "";
  editInstruction.value = "";
}
function closeEditor() {
  editing.value = null;
}
async function applyInstruction() {
  const number = editing.value;
  const instruction = editInstruction.value.trim();
  if (!number || !instruction) return;
  if (!confirm(`让模型按「${instruction}」改写第 ${number} 段？\n\n只改这一段讲稿，改完只重新合成这一段，其余段不动。`)) return;
  if (await props.audio.reviseSegment(number, { instruction })) closeEditor();
}
async function saveEditedText() {
  const number = editing.value;
  const text = editText.value.trim();
  if (!number || !text) return;
  if (!confirm(`保存第 ${number} 段的手工修改，并重新合成这一段？`)) return;
  if (await props.audio.reviseSegment(number, { text })) closeEditor();
}
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
async function loadFullScript() {
  scriptBusy.value = true;
  try {
    scriptInfo.value = await props.audio.loadScript();
    scriptOpen.value = !!scriptInfo.value;
  } catch (error) { state.notice = error.message; }
  finally { scriptBusy.value = false; }
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
      <div v-if="canRevoice" class="row revoice-row">
        <select v-model="revoiceVoice" :disabled="playback.revoicing">
          <option value="">换一个音色重念…</option>
          <option v-for="voice in revoiceOptions" :key="voice.name" :value="voice.name">
            {{ voice.display_name || voice.name }}
          </option>
        </select>
        <button :disabled="!revoiceVoice || playback.revoicing || revoiceBlocked" @click="confirmRevoice">
          {{ playback.revoicing ? "正在重念…" : "换音色重制" }}
        </button>
      </div>
      <p v-if="canRevoice" class="muted">
        <template v-if="revoiceBlocked">正在生成内容，等这批片段完成后再换音色。</template>
        <template v-else-if="revoiceStartsOver">这段音频还没有可用的声音（上次失败或被中断），换音色会直接按已有讲稿重新合成全部片段。</template>
        <template v-else>讲稿与内容不变，只重新合成语音；原音色的音频保留 8 小时后自动清理。</template>
      </p>
      <div v-if="currentScript" class="card script-card">
        <div class="row spaced">
          <h3>这段的讲稿</h3>
          <span class="badge">{{ currentScript.length }} 字</span>
        </div>
        <pre :class="{ clamped: scriptClamp }">{{ currentScript }}</pre>
        <div class="row">
          <button v-if="currentScript.length > 420" @click="scriptExpanded = !scriptExpanded">
            {{ scriptExpanded ? "收起" : "展开全文" }}
          </button>
          <small class="muted">改这段的入口在下方「分段与任务详情」里。</small>
        </div>
      </div>
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
          <div v-if="editing !== segment.segment_number" class="row">
            <button :disabled="state.busy || playback.revising != null" @click="openEditor(segment)">改这段讲稿</button>
            <small class="muted">{{ (segment.script || "").length }} 字</small>
          </div>
          <div v-else class="script-editor">
            <label>这段的讲稿<textarea v-model="editText" rows="6" maxlength="20000" /></label>
            <label>想怎么改（可选）
              <input v-model="editInstruction" maxlength="300"
                placeholder="例如：太书面了，改口语一点，保留原来的例子" @keydown.enter.prevent="applyInstruction" />
            </label>
            <div class="row">
              <button :disabled="playback.revising != null || !editInstruction.trim()" @click="applyInstruction">
                {{ playback.revising === segment.segment_number ? "正在改写…" : "按意见修改" }}
              </button>
              <button :disabled="playback.revising != null || !editText.trim()" @click="saveEditedText">保存我的修改</button>
              <button @click="closeEditor">取消</button>
            </div>
            <small class="muted">只改并重合成这一段，其余段不动；旧音频保留 8 小时后自动清理。</small>
          </div>
          <details v-if="segment.audit?.length"><summary>操作记录</summary><pre>{{ JSON.stringify(segment.audit, null, 2) }}</pre></details>
        </article>
        <small>任务 {{ playback.queue.queue_id }} · {{ playback.queue.status }}</small>
      </details>
    </div>
    <div v-if="active" class="card">
      <div class="row spaced"><h3>完整讲稿</h3><span class="badge">{{ scriptInfo ? scriptInfo.characters + " 字" : (playback.queue.lessons?.length || 0) + " 段" }}</span></div>
      <p class="muted">把各段正文合并成一份，导出后是一个文件，可直接保存或继续修改。</p>
      <div class="row">
        <button :disabled="scriptBusy" @click="loadFullScript">读取完整讲稿</button>
        <button :disabled="scriptBusy" @click="exportFullScript">导出为文件</button>
        <button v-if="scriptInfo" @click="scriptOpen = !scriptOpen">{{ scriptOpen ? "收起全文" : "展开全文" }}</button>
      </div>
      <pre v-if="scriptInfo && scriptOpen">{{ scriptInfo.script }}</pre>
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
