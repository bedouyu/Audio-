<script setup>
import { ref } from "vue";
import { ArrowUp, Headphones, Mic } from "lucide-vue-next";
import VoiceImportDialog from "./VoiceImportDialog.vue";
const props = defineProps({ audio: Object, compact: Boolean });
const emit = defineEmits(["submit"]);
const { state: playback } = props.audio;
const instruction = ref("");
const field = ref(null);
const importOpen = ref(false);
const importing = ref(false);
async function importVoice(payload) {
  importing.value = true;
  try {
    const voice = await props.audio.importVoice(payload);
    playback.voice = voice.name;
    playback.voiceStatus = "已录入音色：" + (voice.display_name || voice.name) + "，下次生成会用它朗读。";
    importOpen.value = false;
  } catch (error) {
    playback.error = error.message;
  } finally {
    importing.value = false;
  }
}
const examples = ["帮我整理最近的时政，做成 10 分钟简报", "把我导入的小说按章节读给我听", "讲一段适合练听力的英文故事，再解释难句"];
function submit() {
  if (instruction.value.trim() && !playback.submitting) emit("submit", instruction.value.trim());
}
function useExample(text) { instruction.value = text; field.value?.focus(); }
function voiceLanguage(voice) {
  const language = String(voice.language || voice.locale || "").toLowerCase();
  if (language === "bilingual") return "中英双语";
  if (language.startsWith("zh")) return "中文";
  if (language.startsWith("en")) return "英语";
  return language;
}
</script>
<template>
  <div class="request-block" :class="{ compact }">
    <form class="listening-composer" @submit.prevent="submit">
      <label class="sr-only" for="listening-instruction">你想听什么</label>
      <textarea id="listening-instruction" ref="field" v-model="instruction" maxlength="4000" :rows="compact ? 3 : 4"
        placeholder="告诉我你想听什么，也可以说明语言、时长和表达方式…"
        :disabled="playback.submitting" @keydown.enter.exact.prevent="submit" />
      <div class="listening-composer-bottom">
        <span><Headphones :size="15" /> 一句话，开始收听</span>
        <button class="primary" :disabled="playback.submitting || !instruction.trim()" type="submit">
          {{ playback.submitting ? "正在安排…" : "开始制作" }} <ArrowUp :size="16" />
        </button>
      </div>
    </form>
    <p v-if="playback.error" class="request-error" role="alert">{{ playback.error }}</p>
    <div v-if="!compact" class="instruction-examples" aria-label="试试这样说">
      <span class="muted">试试这样说</span>
      <button v-for="example in examples" :key="example" :disabled="playback.submitting" @click="useExample(example)">{{ example }}</button>
    </div>
    <details class="voice-options">
      <summary>音色偏好（可选）</summary>
      <label>使用音色
        <select v-model="playback.voice" :disabled="playback.submitting">
          <option value="">自动选择</option>
          <option v-for="voice in playback.voices" :key="voice.name" :value="voice.name">
            {{ voice.display_name || voice.name }}{{ voiceLanguage(voice) ? " · " + voiceLanguage(voice) : "" }}
          </option>
        </select>
      </label>
      <p class="muted">{{ playback.voiceStatus }}</p>
      <button type="button" class="import-voice" :disabled="playback.submitting || importing" @click="importOpen = true">
        <Mic :size="15" /> 录入我的音色（上传或录音）
      </button>
    </details>
    <VoiceImportDialog :open="importOpen" :busy="importing" @close="importOpen = false" @submit="importVoice" />
  </div>
</template>
