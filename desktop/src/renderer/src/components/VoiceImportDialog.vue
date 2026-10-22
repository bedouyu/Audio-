<script setup>
import { computed, ref, watch } from "vue";
import { Mic, Square, Upload } from "lucide-vue-next";
import { durationHint, MAX_SECONDS, sampleFromAudioBuffer, toBase64 } from "../../../shared/speech-sample.js";

const props = defineProps({ open: Boolean, busy: Boolean });
const emit = defineEmits(["close", "submit"]);
const displayName = ref("");
const promptText = ref("");
const languages = ref(["zh"]);
const sample = ref(null);
const status = ref("");
const recording = ref(false);
const recordedSeconds = ref(0);
let recorder = null;
let stream = null;
let timer = null;
let chunks = [];

const ready = computed(() => displayName.value.trim() && promptText.value.trim() && sample.value && !props.busy);
const hint = computed(() => {
  if (!sample.value) return status.value;
  const note = durationHint(sample.value.seconds);
  const exact = sample.value.seconds > MAX_SECONDS ? "超出上限会被服务端拒绝。" : "";
  return note + exact;
});

watch(() => props.open, (value) => {
  if (value) reset();
  else stopRecording();
});

function reset() {
  displayName.value = "";
  promptText.value = "";
  languages.value = ["zh"];
  sample.value = null;
  status.value = "";
  recordedSeconds.value = 0;
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

function toggleLanguage(code) {
  const next = languages.value.includes(code)
    ? languages.value.filter((item) => item !== code)
    : [...languages.value, code];
  if (next.length) languages.value = next;
}

function submit() {
  if (!ready.value) return;
  emit("submit", {
    name: "clone-" + Date.now().toString(36),
    display_name: displayName.value.trim(),
    languages: languages.value,
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
          录一段 3—30 秒的清晰人声（安静环境、单人、不要背景音乐），并填入你在这段录音里说的那句原话。
          系统只对样例做一次特征提取并缓存，不训练模型，几秒内即可使用。
        </p>
        <label>音色名称<input v-model="displayName" maxlength="80" placeholder="例如：我的声音" /></label>
        <label>样例文字（必须与录音逐字一致）
          <textarea v-model="promptText" rows="3" maxlength="300" placeholder="把录音里说的那句话原样写在这里" />
        </label>
        <div class="row voice-languages">
          <span class="muted">适用语言</span>
          <button type="button" :class="{ active: languages.includes('zh') }" @click="toggleLanguage('zh')">中文</button>
          <button type="button" :class="{ active: languages.includes('en') }" @click="toggleLanguage('en')">英语</button>
        </div>
        <div class="row voice-source">
          <label class="file-pick">
            <Upload :size="15" /> 选择音频文件
            <input type="file" accept="audio/*" @change="pickFile" />
          </label>
          <button v-if="!recording" type="button" :disabled="busy" @click="startRecording">
            <Mic :size="15" /> 开始录音
          </button>
          <button v-else type="button" class="recording" @click="stopRecording">
            <Square :size="15" /> 停止录音 · {{ recordedSeconds.toFixed(1) }} 秒
          </button>
        </div>
        <p v-if="hint" class="muted">{{ hint }}</p>
        <small class="muted">只录入你本人或已获授权的声音；样例文字的准确度直接影响音色还原效果。</small>
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
.voice-dialog { width: min(620px, 100%); max-height: 90vh; overflow: auto; background: var(--panel, #fff); color: #263238; padding: 24px; border-radius: 14px; box-shadow: 0 16px 60px #0004; display: grid; gap: 12px; }
.voice-dialog label { display: grid; gap: 6px; }
.voice-dialog input[type="text"], .voice-dialog input:not([type]), .voice-dialog textarea { width: 100%; }
.voice-languages button.active { background: #246659; color: #fff; border-color: #246659; }
.voice-source .file-pick { display: inline-flex; align-items: center; gap: 7px; padding: 9px 13px; border: 1px solid #dfe3e8; border-radius: 8px; cursor: pointer; }
.voice-source input[type="file"] { display: none; }
.voice-source .recording { background: #b23b3b; color: #fff; border-color: #b23b3b; }
</style>
