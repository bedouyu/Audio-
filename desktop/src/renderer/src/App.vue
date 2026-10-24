<script setup>
import { ref, onMounted } from "vue";
import {
  Headphones,
  ArrowLeft,
  Files,
  AudioLines,
  ListChecks,
  Activity,
  LogOut,
  RefreshCw,
  Layers,
} from "lucide-vue-next";
import { useWorkspace } from "./composables/useWorkspace";
import ProjectDashboard from "./components/ProjectDashboard.vue";
import KnowledgePanel from "./components/KnowledgePanel.vue";
import AudioPanel from "./components/AudioPanel.vue";
import ChatPanel from "./components/ChatPanel.vue";
import AgentWorkspacePanel from "./components/AgentWorkspacePanel.vue";
import RetrievalDecision from "./components/RetrievalDecision.vue";
import MemoryDecision from "./components/MemoryDecision.vue";
import ModelBudgetDecision from "./components/ModelBudgetDecision.vue";
import RuntimeSummary from "./components/RuntimeSummary.vue";
import GlobalAudioPlayer from "./components/GlobalAudioPlayer.vue";
import { useAudioCenter } from "./composables/useAudioCenter";
const workspace = useWorkspace();
const audio = useAudioCenter(workspace);
const { state, api, refreshProjects, refreshProject, openProject, safe } =
  workspace;
const username = ref("");
const password = ref("");
const tab = ref("audio");
const trace = ref([]);
const health = ref(null);
async function authenticate(mode) {
  await safe(async () => {
    const result = await api("/api/v1/auth/" + mode, "POST", {
      username: username.value,
      password: password.value,
    });
    state.token = result.token;
    state.user = result.user;
    localStorage.setItem("audiorag_token", state.token);
    password.value = "";
    await refreshProjects();
  });
}
async function submitAudio(instruction) {
  await safe(async () => {
    const result = await audio.submit(instruction, state.project);
    if (!result) return;
    await refreshProjects();
    await open(result.project);
  });
}
async function playHistory(queue) {
  await safe(async () => {
    await audio.openQueue(queue);
    if (state.user && audio.state.project) await open(audio.state.project);
  });
}
async function createProject(topic) {
  await safe(async () => {
    const project = await api("/api/v1/projects", "POST", { topic });
    await refreshProjects();
    await open(project);
  });
}
async function importSources() {
  const userId = state.user?.id;
  await safe(async () => {
    const project = await api("/api/v1/projects", "POST", { topic: "我的资料" });
    if (state.user?.id !== userId) return;
    await refreshProjects();
    if (state.user?.id !== userId) return;
    trace.value = []; tab.value = "knowledge";
    await openProject(project);
  });
}
async function remove(project) {
  if (
    !confirm(
      "确认删除“" +
        project.title +
        "”的内容空间、资料、会话和生成音频？此操作无法撤销。",
    )
  )
    return;
  await safe(async () => {
    await api("/api/v1/projects/" + project.id, "DELETE");
    audio.forgetProject(project.id);
    if (state.project?.id === project.id) state.project = null;
    await refreshProjects();
  });
}
async function open(project) {
  if (!project) return;
  tab.value = "audio";
  trace.value = [];
  await safe(() => openProject(project));
  audio.resumeLegacyProject(project);
}
async function logout() {
  const token = state.token;
  audio.save(true);
  // 先趁令牌还有效时通知服务端删除它；后端不可用也不阻断本地退出。
  if (token) {
    try {
      await api("/api/v1/auth/logout", "POST");
    } catch {
      /* 离线退出：本地清空即可 */
    }
  }
  audio.clear();
  state.user = null;
  state.project = null;
  state.token = "";
  localStorage.removeItem("audiorag_token");
}
async function loadTrace(record) {
  await safe(async () => {
    trace.value = (
      await api("/api/v1/sessions/" + record.metadata.session_id + "/trace")
    ).events;
  });
}
onMounted(async () => {
  state.backend = (await window.audioAgent.info()).backend;
  let backendUp = false;
  try {
    health.value = await api("/api/health");
    state.agentRuntime =
      health.value.generation?.agent_runtime || "offline-retrieval";
    backendUp = true;
  } catch {
    state.notice = "请先启动 Java 后端：" + state.backend;
  }
  // 后端没起来时不要拿 /me 的失败当"登录失效"，否则每次先开桌面端都会丢掉已保存的令牌。
  if (state.token && backendUp) {
    await safe(async () => {
      try {
        state.user = await api("/api/v1/me");
      } catch {
        await logout();
        return;
      }
      await refreshProjects();
    });
  }
});
</script>
<template>
  <div class="application" :class="{ 'auth-mode': !state.user }">
    <header class="app-bar">
      <div class="brand">
        <Headphones :size="22" /><span>Audio智能助手</span
        ><span class="brand-sub">你的 AI 音频中心</span>
      </div>
      <div class="row">
        <button v-if="state.user && state.project" class="icon" @click="state.project = null">首页</button>
        <span class="connection" :class="{ connected: health }">{{
          health ? "本机已连接" : "后端未连接"
        }}</span
        ><span v-if="state.user" class="muted">{{ state.user.username }}</span
        ><button
          v-if="state.user"
          class="icon"
          title="退出登录"
          @click="logout"
        >
          <LogOut :size="17" />
        </button>
      </div>
    </header>
    <div v-if="state.notice" class="notice" role="status">
      {{ state.notice
      }}<button class="icon" @click="state.notice = ''">×</button>
    </div>
    <main v-if="!state.user" class="auth-screen">
      <div class="auth-card">
        <div class="eyebrow">YOUR PERSONAL AUDIO CENTER</div>
        <h1>让想听的内容，来到耳边。</h1>
        <p class="muted">登录后，一句话开始制作，收听进度与内容按账号保存。</p>
        <label
          >用户名<input
            v-model="username"
            autocomplete="username"
            minlength="2"
            maxlength="40" /></label
        ><label
          >密码<input
            v-model="password"
            type="password"
            autocomplete="current-password"
            @keydown.enter="authenticate('login')"
        /></label>
        <div class="row">
          <button
            class="primary"
            :disabled="state.busy"
            @click="authenticate('login')"
          >
            登录</button
          ><button :disabled="state.busy" @click="authenticate('register')">
            注册
          </button>
        </div>
        <small class="muted">后端地址：{{ state.backend }}</small>
      </div>
    </main>
    <ProjectDashboard
      v-else-if="!state.project"
      :projects="state.projects"
      :busy="state.busy"
      :audio="audio"
      @create="createProject"
      @open="open"
      @play="playHistory"
      @import="importSources"
      @delete="remove"
    />
    <main v-else class="workspace">
      <aside class="sidebar">
        <button class="back-button" @click="state.project = null">
          <ArrowLeft :size="16" /> 返回首页
        </button>
        <div class="project-label">当前内容空间</div>
        <h3>{{ state.project.title }}</h3>
        <p class="muted">{{ state.project.topic }}</p>
        <nav>
          <button :class="{ active: tab === 'audio' }" @click="tab = 'audio'">
            <AudioLines :size="17" /> 收听与制作</button>
          <button
            :class="{ active: tab === 'knowledge' }"
            @click="tab = 'knowledge'"
          >
            <Files :size="17" /> 资料</button>
          <details class="advanced-navigation"><summary>更多工具</summary>
            <button :class="{ active: tab === 'agent' }" @click="tab = 'agent'"><Layers :size="17" /> 制作草稿与产物</button>
            <button :class="{ active: tab === 'plan' }" @click="tab = 'plan'"><ListChecks :size="17" /> 旧学习计划</button>
            <button :class="{ active: tab === 'trace' }" @click="tab = 'trace'"><Activity :size="17" /> 执行记录</button>
          </details>
        </nav>
        <div class="project-label">资料文件 · {{ state.documents.length }}</div>
        <button
          v-for="item in state.documents"
          :key="item.id"
          class="file-item"
          @click="tab = 'knowledge'"
        >
          <Files :size="14" /> {{ item.metadata?.filename || item.title }}
        </button>
        <div class="sidebar-bottom">
          <button :disabled="state.busy" @click="safe(refreshProject)">
            <RefreshCw :size="14" /> 刷新工作区</button
          ><small>你的资料与对话保存在这个空间中</small>
        </div>
      </aside>
      <ChatPanel
        :workspace="workspace"
        :audio="audio"
        :key="state.project.id"
        @trace="trace = $event"
        @show-artifacts="tab = 'agent'"
        @answered="audio.refresh()"
      />
      <div class="content-pane">
        <KnowledgePanel
          v-show="tab === 'knowledge'"
          :workspace="workspace"
        /><AudioPanel
          v-show="tab === 'audio'"
          :workspace="workspace"
          :audio="audio"
          :key="state.project.id"
          @submit="submitAudio"
        />
        <AgentWorkspacePanel
          v-if="tab === 'agent'"
          :workspace="workspace"
          @trace="
            trace = $event;
            tab = 'trace';
          "
        />
        <section v-if="tab === 'plan'" class="panel">
          <div class="eyebrow">PROGRESS</div>
          <h2>旧学习计划</h2>
          <p class="muted">
            这里保留原有课程的生成记录。“已完成”代表对应音频已生成，并不代表已经收听或掌握。
          </p>
          <article
            v-for="(point, index) in state.points"
            :key="point.id"
            class="card row spaced"
          >
            <span>{{ index + 1 }}. {{ point.focus }}</span
            ><span class="badge">{{
              point.status === "COMPLETED"
                ? "已完成"
                : point.status === "QUEUED"
                  ? "生成中"
                  : "待生成"
            }}</span>
          </article>
          <p v-if="!state.points.length" class="empty">
            这个空间没有旧学习计划。新的音频请求会直接按照你的要求准备。
          </p>
        </section>
        <section v-if="tab === 'trace'" class="panel">
          <div class="eyebrow">AGENT ACTIVITY</div>
          <h2>运行记录</h2>
          <p class="muted">查看本轮使用的工具、命中数量与模型上下文大小。</p>
          <RuntimeSummary :events="trace" />
          <article v-for="(event, index) in trace" :key="index" class="card">
            <h3>{{ event.name }}</h3>
            <RetrievalDecision :event="event" />
            <MemoryDecision :event="event" />
            <ModelBudgetDecision :event="event" />
            <pre>{{ JSON.stringify(event.payload, null, 2) }}</pre>
          </article>
          <details
            v-for="record in state.records.filter(
              (item) => item.record_type === 'chat',
            )"
            :key="record.id"
            class="card"
          >
            <summary>
              {{ record.title }} · {{ String(record.created_at).slice(0, 16) }}
            </summary>
            <pre>{{ record.content }}</pre>
            <button @click="loadTrace(record)">打开执行轨迹</button>
          </details>
        </section>
      </div>
    </main>
    <GlobalAudioPlayer v-if="state.user" :audio="audio" @open="open" />
  </div>
</template>
