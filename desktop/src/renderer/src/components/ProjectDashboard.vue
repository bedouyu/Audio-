<script setup>
import { Headphones, ArrowUpRight, Trash2, Upload } from "lucide-vue-next";
import AudioRequestComposer from "./AudioRequestComposer.vue";
import AudioHistory from "./AudioHistory.vue";
defineProps({ projects: Array, busy: Boolean, audio: Object });
const emit = defineEmits(["open", "submit", "delete", "play", "import"]);
</script>
<template>
  <main class="dashboard listening-home">
    <div class="home-intro"><div class="eyebrow">YOUR AUDIO, YOUR WAY</div>
      <h1>今天，想听点什么？</h1>
      <p class="muted">说说你的想法，把关心的内容变成可以听的节目。</p>
    </div>
    <AudioRequestComposer :audio="audio" @submit="emit('submit', $event)" />
    <div class="home-actions"><button :disabled="busy" @click="emit('import')"><Upload :size="16" /> 添加资料</button>
      <span class="muted">导入小说、文章或音频，再告诉我怎么听。</span></div>
    <div class="history-heading"><h2>继续收听</h2><button class="icon" @click="audio.refreshHistory">刷新</button></div>
    <p v-if="audio.state.historyStatus" class="muted">{{ audio.state.historyStatus }}</p>
    <AudioHistory :queues="audio.state.history.slice(0, 12)" :current-id="audio.state.queue?.queue_id" :busy="audio.state.restoring"
      @play="emit('play', $event)" />
    <p v-if="!audio.state.history.length && !audio.state.historyStatus" class="muted">制作好的节目会保存在这里，可以随时接着听。</p>
    <div class="history-heading"><h2>内容空间</h2><span class="muted">已有资料、对话与音频都在这里</span></div>
    <div class="project-grid">
      <article v-for="project in projects" :key="project.id" class="project-card">
        <Headphones :size="23" class="accent" />
        <button class="icon delete" title="删除内容空间" :disabled="busy" @click="emit('delete', project)"><Trash2 :size="16" /></button>
        <h3>{{ project.title }}</h3><p class="muted">{{ project.topic }}</p>
        <button class="open-project" :disabled="busy" @click="emit('open', project)">打开内容 <ArrowUpRight :size="17" /></button>
      </article>
    </div>
    <p v-if="!projects?.length" class="empty">第一次收听，从上面的一句话开始。</p>
  </main>
</template>
