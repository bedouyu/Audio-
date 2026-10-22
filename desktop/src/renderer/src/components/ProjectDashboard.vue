<script setup>
import { ref } from "vue";
import { Headphones, ArrowUpRight, Trash2, Upload, Plus } from "lucide-vue-next";
import AudioHistory from "./AudioHistory.vue";
defineProps({ projects: Array, busy: Boolean, audio: Object });
const emit = defineEmits(["create", "open", "delete", "play", "import"]);
const topic = ref("");
function create() {
  const value = topic.value.trim();
  if (!value) return;
  emit("create", value);
  topic.value = "";
}
</script>
<template>
  <main class="dashboard listening-home">
    <div class="home-intro">
      <div class="eyebrow">WORKSPACE</div>
      <h1>我的内容空间</h1>
      <p class="muted">先建一个内容空间，音频的制作与选择都在空间里完成。资料、对话和音频按空间保存。</p>
    </div>
    <div class="card">
      <h3>新建内容空间</h3>
      <div class="row">
        <input v-model="topic" maxlength="120" placeholder="例如：量子计算入门 / 每日时政简报" @keydown.enter="create" />
        <button class="primary" :disabled="busy || !topic.trim()" @click="create"><Plus :size="16" /> 创建</button>
      </div>
      <p class="muted">创建后直接进入空间，说一句想听什么就会开始准备内容。</p>
    </div>
    <div class="history-heading"><h2>内容空间</h2><span class="muted">共 {{ projects?.length || 0 }} 个</span></div>
    <div class="project-grid">
      <article v-for="project in projects" :key="project.id" class="project-card">
        <Headphones :size="23" class="accent" />
        <button class="icon delete" title="删除内容空间" :disabled="busy" @click="emit('delete', project)"><Trash2 :size="16" /></button>
        <h3>{{ project.title }}</h3><p class="muted">{{ project.topic }}</p>
        <button class="open-project" :disabled="busy" @click="emit('open', project)">进入空间 <ArrowUpRight :size="17" /></button>
      </article>
    </div>
    <p v-if="!projects?.length" class="empty">还没有内容空间，先在上面创建一个。</p>
    <div class="home-actions">
      <button :disabled="busy" @click="emit('import')"><Upload :size="16" /> 导入资料并开空间</button>
      <span class="muted">适合已经有原文（小说、文章、字幕）的情况。</span>
    </div>
    <details v-if="audio.state.history.length" class="card">
      <summary>最近收听 · {{ audio.state.history.length }} 条</summary>
      <AudioHistory :queues="audio.state.history.slice(0, 8)" :current-id="audio.state.queue?.queue_id" :busy="audio.state.restoring" @play="emit('play', $event)" />
    </details>
  </main>
</template>
