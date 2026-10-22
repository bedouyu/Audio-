<script setup>
import { SkipBack, SkipForward, Headphones } from "lucide-vue-next";
const props = defineProps({ audio: Object });
const emit = defineEmits(["open"]);
const { state: playback, current, source, total, message } = props.audio;
function failed() { if (source.value) playback.error = "这段音频暂时无法播放，可以打开内容查看任务状态。"; }
</script>
<template>
  <footer v-if="playback.queue" class="global-player" aria-label="全局音频播放器">
    <button class="player-context" @click="emit('open', playback.project)" :disabled="!playback.project">
      <span class="player-cover"><Headphones :size="23" /></span>
      <span><strong>{{ current?.title || playback.project?.title || "正在准备音频" }}</strong>
        <small :title="playback.error || message">{{ playback.error || (playback.restoring ? "正在恢复收听位置…" : current ? `第 ${playback.index + 1} / ${total} 段 · ${playback.progressStatus}` : message) }}</small></span>
    </button>
    <div class="player-controls">
      <button class="icon" title="上一段" aria-label="上一段" :disabled="playback.index === 0" @click="audio.previous"><SkipBack :size="19" /></button>
      <audio :ref="audio.bindPlayer" :controls="!playback.restoring" preload="auto" :src="source || undefined"
        @loadedmetadata="audio.loaded" @timeupdate="audio.timeUpdate" @ended="audio.next"
        @play="audio.playChanged(true)" @pause="audio.playChanged(false)" @error="failed" />
      <button class="icon" title="下一段" aria-label="下一段" :disabled="playback.index + 1 >= total" @click="audio.next"><SkipForward :size="19" /></button>
    </div>
    <label class="player-speed"><span class="sr-only">播放速度</span>
      <select :value="playback.speed" @change="audio.setSpeed($event.target.value)">
        <option v-for="speed in [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]" :key="speed" :value="speed">{{ speed }}×</option>
      </select>
    </label>
    <button class="player-detail" @click="emit('open', playback.project)" :disabled="!playback.project">查看内容</button>
  </footer>
</template>
