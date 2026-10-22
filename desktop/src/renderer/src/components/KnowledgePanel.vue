<script setup>
import { ref } from "vue";
const props = defineProps({ workspace: Object });
const { state, api, safe, refreshProject } = props.workspace;
const title = ref("");
const transcript = ref("");
async function upload() {
  await safe(async () => {
    const result = await window.audioAgent.upload({
      projectId: state.project.id,
      title: title.value,
      transcript: transcript.value,
      token: state.token,
    });
    if (!result) return;
    title.value = "";
    transcript.value = "";
    await refreshProject();
    state.notice = "资料已导入，回到“收听与制作”描述要求。";
  });
}
async function download(item) {
  await safe(async () => {
    await window.audioAgent.download({
      projectId: state.project.id,
      recordId: item.id,
      filename: item.metadata?.filename || item.title,
      token: state.token,
    });
  });
}
async function remove(item) {
  if (!confirm("删除此资料的原文件和知识库片段？")) return;
  await safe(async () => {
    await api(
      "/api/v1/projects/" + state.project.id + "/knowledge/" + item.id,
      "DELETE",
    );
    await refreshProject();
  });
}
</script>
<template>
  <section class="panel">
    <div class="panel-heading">
      <div>
        <div class="eyebrow">KNOWLEDGE</div>
        <h2>这个空间的资料</h2>
      </div>
      <span class="badge">{{ state.documents.length }} 份资料</span>
    </div>
    <div class="card">
      <h3>导入资料</h3>
      <p class="muted">
        支持文本、Markdown、字幕和音频。音频可附加转写文字，或使用已配置的转写服务。
      </p>
      <p class="muted">导入后，可以回到“收听与制作”，直接告诉我如何朗读、整理或讲解这些内容。</p>
      <label
        >资料标题（可选）<input
          v-model="title"
          placeholder="留空使用文件名"
          maxlength="500"
      /></label>
      <label
        >转写文字（可选）<textarea
          v-model="transcript"
          rows="3"
          placeholder="音频没有转写服务时，在这里粘贴文本"
        />
      </label>
      <button class="primary" :disabled="state.busy" @click="upload">
        选择文件并导入
      </button>
    </div>
    <article
      v-for="item in state.documents"
      :key="item.id"
      class="card document-card"
    >
      <div>
        <h3>{{ item.title }}</h3>
        <p class="muted">
          {{ item.metadata?.filename }} ·
          {{ item.metadata?.index_version === 'canonical-v2' ? '支持原文朗读' : '旧资料，原文朗读需重新导入' }}
        </p>
        <details><summary>导入详情</summary><small>{{ item.metadata?.chunks || 0 }} 个片段 · {{ item.metadata?.index_version || '旧索引' }}{{ item.metadata?.document_type ? ' · ' + item.metadata.document_type : '' }}</small></details>
      </div>
      <div class="row">
        <button :disabled="state.busy" @click="download(item)">
          下载原文件</button
        ><button class="danger" :disabled="state.busy" @click="remove(item)">
          删除
        </button>
      </div>
    </article>
    <p v-if="!state.documents.length" class="empty">
      导入你想听的资料，让音频助理按你的要求准备内容。
    </p>
  </section>
</template>
