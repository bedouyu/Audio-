<script setup>
import { computed, ref, watch } from "vue";
import { Mic, Square, Upload } from "lucide-vue-next";
import {
  MAX_SECONDS, durationHint, expectedSeconds, readingVerdict, sampleFromAudioBuffer, toBase64,
} from "../../../shared/speech-sample.js";
import { scriptsFor } from "../../../shared/voice-scripts.js";

const props = defineProps({ open: Boolean, busy: Boolean });
const emit = defineEmits(["close", "submit"]);
const mode = ref("record");
const displayName = ref("");
const scriptLanguage = ref("zh");
const scriptId = ref("zh-daily");
// 样例读哪种语言 ≠ 这个音色可用于哪些语言：零样本克隆能跨语言念，只是会带口音。
const usable = ref(["zh"]);
const typedText = ref("");
const sample = ref(null);
const status = ref("");
const recording = ref(false);
const recordedSeconds = ref(0);
let recorder = null;
let stream = null;
let timer = null;
let chunks = [];

const scripts = computed(() => scriptsFor(scriptLanguage.value));
const script = computed(() => scripts.value.find((item) => item.id === scriptId.value) || scripts.value[0]);
const promptText = computed(() => (mode.value === "record" ? script.value.text : typedText.value));
const expected = computed(() => expectedSeconds(promptText.value));
const verdict = computed(() => {
  if (!sample.value) return "ok";
  return readingVerdict(sample.value.seconds, mode.value === "record" ? expected.value : 0);
});
const ready = computed(() => Boolean(
  displayName.value.trim() && promptText.value.trim() && sample.value && !props.busy && verdict.value !== "short",
));
const hint = computed(() => {
  if (!sample.value) return status.value;
  const note = durationHint(sample.value.seconds);
  if (verdict.value === "short") {
    return `录音只有 ${sample.value.seconds.toFixed(1)} 秒，而这段话大约需要 ${expected.value.toFixed(0)} 秒，可能没有读完——请重读一遍，或改用上传文件。`;
  }
  if (verdict.value === "long") return note + " 录音明显长于这段话，请确认没有额外内容。";
  return note;
});

watch(() => props.open, (value) => {
  if (value) reset();
  else stopRecording();
});

function reset() {
  mode.value = "record";
  displayName.value = "";
  scriptLanguage.value = "zh";
  usable.value = ["zh"];
  scriptId.value = "zh-daily";
  typedText.value = "";
  sample.value = null;
  status.value = "";
  recordedSeconds.value = 0;
}

function pickScript(item) {
  scriptId.value = item.id;
  sample.value = null;
  status.value = "";
}

function setLanguage(code) {
  const previous = scriptLanguage.value;
  scriptLanguage.value = code;
  // 用户没单独改过"可用于"时，跟着样例语言一起走。
  if (usable.value.length === 1 && usable.value[0] === previous) usable.value = [code];
  const first = scriptsFor(code)[0];
  scriptId.value = first.id;
  sample.value = null;
}

function toggleUsable(code) {
  const next = usable.value.includes(code)
    ? usable.value.filter((item) => item !== code)
    : [...usable.value, code];
  if (next.length) usable.value = next;
}

async function decode(arrayBuffer) {
  const context = new AudioContext();
  try {
    const buffer = await context.decodeAudioData(arrayBuffer);
    const result = sampleFromAudioBuffer(buffer);
    sample.value = { base64: toBase64(result.wav), seconds: result.seconds };
    status.value = "";
  } finally {
    await context.close().catch(() => {});
  }
}

async function pickFile(event) {
  const file = event.target.files?.[0];
  event.target.value = "";
  if (!file) return;
  status.value = "正在读取音频…";
  try {
    await decode(await file.arrayBuffer());
  } catch (error) {
    status.value = "无法解码这个文件，请换一段 WAV 或常见的音频文件：" + error.message;
  }
}

async function startRecording() {
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
    });
  } catch (error) {
    status.value = "无法访问麦克风：" + error.message;
    return;
  }
  chunks = [];
  recorder = new MediaRecorder(stream);
  recorder.ondataavailable = (event) => { if (event.data.size) chunks.push(event.data); };
  recorder.onstop = async () => {
    stream?.getTracks().forEach((track) => track.stop());
    stream = null;
    const blob = new Blob(chunks, { type: recorder?.mimeType || "audio/webm" });
    status.value = "正在处理录音…";
    try {
      await decode(await blob.arrayBuffer());
    } catch (error) {
      status.value = "无法解码录音：" + error.message;
    }
  };
  recorder.start();
  recording.value = true;
  recordedSeconds.value = 0;
  const startedAt = Date.now();
  timer = setInterval(() => { recordedSeconds.value = (Date.now() - startedAt) / 1000; }, 200);
}

function stopRecording() {
  if (timer) { clearInterval(timer); timer = null; }
  if (recorder && recorder.state !== "inactive") recorder.stop();
  recording.value = false;
}

function submit() {
  if (!ready.value) return;
  emit("submit", {
    name: "clone-" + Date.now().toString(36),
    display_name: displayName.value.trim(),
    languages: [...usable.value],
    prompt_text: promptText.value.trim(),
    wav_base64: sample.value.base64,
  });
}
</script>
<template>
  <Teleport to="body">
    <div v-if="open" class="voice-backdrop">
      <form role="dialog" aria-modal="true" class="voice-dialog" @submit.prevent="submit">
        <h3>录入新音色</h3>
        <p class="muted">
          录音模式下照着屏幕上的文字读即可，文字会自动作为样例文本，不需要你另外输入；
          已经有录音文件时改用“上传文件”。样例 3—30 秒、安静环境、单人即可。
        </p>
        <label>音色名称<input v-model="displayName" maxlength="80" placeholder="例如：我的声音" /></label>

        <div class="mode-switch">
          <button type="button" :class="{ active: mode === 'record' }" @click="mode = 'record'">录音</button>
          <button type="button" :class="{ active: mode === 'file' }" @click="mode = 'file'">上传文件</button>
        </div>

        <div class="row voice-languages">
          <span class="muted">这个音色可用于</span>
          <button type="button" :class="{ active: usable.includes('zh') }" @click="toggleUsable('zh')">中文</button>
          <button type="button" :class="{ active: usable.includes('en') }" @click="toggleUsable('en')">英语</button>
        </div>
        <p class="muted">
          只勾一种语言时，这个音色只用于该语言；两种都勾就能念中英混排内容。跨语言念会带上样例语言的口音。
        </p>

        <template v-if="mode === 'record'">
          <div class="row">
            <span class="muted">样例语言</span>
            <button type="button" :class="{ active: scriptLanguage === 'zh' }" @click="setLanguage('zh')">中文</button>
            <button type="button" :class="{ active: scriptLanguage === 'en' }" @click="setLanguage('en')">英语</button>
          </div>
          <div class="script-box">
            <p class="muted">请照着下面这段话读，不要改词、不要省略：</p>
            <blockquote>{{ script.text }}</blockquote>
            <div class="row">
              <button v-for="item in scripts" :key="item.id" type="button"
                :class="{ active: item.id === scriptId }" @click="pickScript(item)">{{ item.label }}</button>
            </div>
          </div>
          <div class="row">
            <button v-if="!recording" type="button" :disabled="busy" @click="startRecording">
              <Mic :size="15" /> 开始朗读
            </button>
            <button v-else type="button" class="recording" @click="stopRecording">
              <Square :size="15" /> 停止录音 · {{ recordedSeconds.toFixed(1) }} 秒
            </button>
            <button v-if="sample" type="button" :disabled="busy || recording" @click="startRecording">重录</button>
            <span class="muted">这段话大约需要 {{ expected.toFixed(0) }} 秒</span>
          </div>
        </template>

        <template v-else>
          <label class="file-pick">
            <Upload :size="15" /> 选择音频文件
            <input type="file" accept="audio/*" @change="pickFile" />
          </label>
          <label>样例文字（必须与录音逐字一致）
            <textarea v-model="typedText" rows="3" maxlength="300" placeholder="把录音里说的那句话原样写在这里" />
          </label>
        </template>

        <p v-if="hint" :class="verdict === 'short' ? 'request-error' : 'muted'">{{ hint }}</p>
        <small class="muted">只录入你本人或已获授权的声音；样例文字不准确会让音色走样。</small>
        <div class="row">
          <button type="button" @click="emit('close')">取消</button>
          <button class="primary" :disabled="!ready">{{ busy ? "正在录入…" : "录入并试用" }}</button>
        </div>
      </form>
    </div>
  </Teleport>
</template>
<style scoped>
.voice-backdrop { position: fixed; inset: 0; z-index: 100; background: #0008; display: grid; place-items: center; padding: 20px; }
.voice-dialog { width: min(640px, 100%); max-height: 90vh; overflow: auto; background: var(--panel, #fff); color: #263238; padding: 24px; border-radius: 14px; box-shadow: 0 16px 60px #0004; display: grid; gap: 12px; }
.voice-dialog label { display: grid; gap: 6px; }
.voice-dialog input[type="text"], .voice-dialog input:not([type]), .voice-dialog textarea { width: 100%; }
.mode-switch, .voice-dialog .row button.active { }
.mode-switch { display: flex; gap: 8px; }
.mode-switch button.active, .voice-dialog button.active { background: #246659; color: #fff; border-color: #246659; }
.script-box { border: 1px solid #dfe3e8; border-radius: 10px; padding: 12px 14px; background: #f8fafb; display: grid; gap: 8px; }
.script-box blockquote { margin: 0; font-size: 1.12rem; line-height: 1.9; letter-spacing: 0.02em; }
.file-pick { display: inline-flex; align-items: center; gap: 7px; padding: 9px 13px; border: 1px solid #dfe3e8; border-radius: 8px; cursor: pointer; justify-self: start; }
.file-pick input[type="file"] { display: none; }
.voice-dialog .recording { background: #b23b3b; color: #fff; border-color: #b23b3b; }
</style>
