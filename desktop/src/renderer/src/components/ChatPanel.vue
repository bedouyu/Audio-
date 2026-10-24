<script setup>
import { ref, nextTick, onBeforeUnmount, onMounted } from "vue";
import { Send, Plus, Square } from "lucide-vue-next";
import RetrievalDecision from "./RetrievalDecision.vue";
import MemoryDecision from "./MemoryDecision.vue";
import ModelBudgetDecision from "./ModelBudgetDecision.vue";
import { memoryLabels } from "../../../shared/memory-trace.js";
import { chatAudioQueueId } from "../../../shared/audio-center-policy.js";
import { createRequestWatchdog } from "../../../shared/request-watchdog.js";
const props = defineProps({ workspace: Object, audio: Object });
const emit = defineEmits(["trace", "show-artifacts", "answered"]);
const { state, api, refreshProject } = props.workspace;
const input = ref("");
const messages = ref([]);
const sessionId = ref(null);
const busy = ref(false);
const status = ref("");
const list = ref(null);
const stepLabels = {
  model_start: "思考", model_end: "想好了",
  tool_start: "执行", tool_end: "完成", tool_heartbeat: "处理中",
  delegate_start: "委派", delegate_end: "委派完成",
  artifact: "产物", retrieval: "检索", memory_prepare: "回忆",
  context_budget: "预算检查", task_specification: "拆解任务",
  agent_finish: "收尾", node_heartbeat: "心跳",
};
function stepIcon(step) {
  if (step.kind === "model_start" || step.kind === "model_end") return "🧠";
  if (["tool_start", "tool_end", "tool_heartbeat"].includes(step.kind)) return "🔧";
  if (step.kind === "artifact") return "📄";
  if (step.kind === "delegate_start" || step.kind === "delegate_end") return "🤝";
  return "·";
}
function stepTool(step) {
  return stepLabels[step.kind] || step.tool || step.kind || "";
}
function stepTone(step) {
  if (step.status === "FAILED") return "failed";
  if (step.status === "RUNNING") return "running";
  return "";
}
const activity = ref([]);
const memoryStrategy = ref("");
const agentLabels = {
  supervisor: "音频助理",
  study_planner: "内容规划",
  research_agent: "资料研究",
  script_writer: "文字稿编写",
  review_agent: "内容复核",
};
let streamId = null;
let mounted = true;
const watchdog = createRequestWatchdog();
async function scroll() {
  await nextTick();
  if (list.value) list.value.scrollTop = list.value.scrollHeight;
}
async function selectSession(id) {
  if (busy.value) return;
  sessionId.value = id || null;
  activity.value = [];
  messages.value = [];
  status.value = "";
  if (id) {
    try {
      messages.value = (
        await api(
          "/api/v1/projects/" +
            state.project.id +
            "/sessions/" +
            id +
            "/messages",
        )
      ).messages;
      await scroll();
    } catch (error) {
      status.value = error.message;
    }
  }
}
async function send() {
  const text = input.value.trim();
  if (!text || busy.value) return;
  input.value = "";
  busy.value = true;
  status.value = "正在检索…";
  activity.value = [];
  messages.value.push({ role: "user", content: text });
  const answer = { role: "assistant", content: "", citations: [] };
  messages.value.push(answer);
  const index = messages.value.length - 1;
  const requestId = crypto.randomUUID();
  streamId = requestId;
  const active = () => mounted && watchdog.isCurrent(requestId);
  // 只在连续 150 秒没有有效进展时停止；长工具处理中的心跳会续期。
  watchdog.begin(requestId, () => {
    if (!mounted || streamId !== requestId) return;
    window.audioAgent.cancel(requestId);
    status.value = "长时间没有收到新进展，已停止这次请求；请稍后重试。";
    messages.value[index].content ||= status.value;
    messages.value[index].failed = true;
    streamId = null;
    busy.value = false;
  });
  try {
    await window.audioAgent.stream(
      requestId,
      {
        message: text,
        session_id: sessionId.value,
        project_id: state.project.id,
        audio_queue_id: chatAudioQueueId(props.audio?.state, state.project.id),
        memory_strategy: memoryStrategy.value || undefined,
        request_id: requestId,
      },
      state.token,
      (event) => {
        if (!active() || !["TOKEN", "STATUS", "TOOL", "ERROR", "RESULT"].includes(event?.type)) return;
        watchdog.touch(requestId);
        if (event.type === "TOKEN") {
          messages.value[index].content += event.data;
          scroll();
        }
        if (event.type === "STATUS") status.value = event.data;
        if (event.type === "TOOL") {
          const step = event.data;
          if (step.event_id && activity.value.some(item => item.event_id === step.event_id)) return;
          if (step.kind) {
            status.value =
              (agentLabels[step.agent_id] || step.agent_id) +
              " · " +
              step.detail;
            if (step.kind === "model_start" && step.agent_id === "supervisor")
              messages.value[index].content = "";
            activity.value.push({
              ...step,
              label: agentLabels[step.agent_id] || step.agent_id,
            });
          } else
            status.value =
              (step.tool === "knowledge_search" ? "本机检索" : "网络补充") +
              "：" +
              step.hit_count +
              " 条";
        }
        if (event.type === "ERROR") throw new Error(event.data);
        if (event.type === "RESULT") {
          sessionId.value = event.data.session_id;
          messages.value[index].content = event.data.answer;
          messages.value[index].citations = event.data.citations || [];
          messages.value[index].artifactCount =
            event.data.artifacts?.length || 0;
          messages.value[index].stats = event.data.stats;
          messages.value[index].taskStatus = event.data.status;
          // 运行失败时后端只返回 status，没有 answer；必须给出可读的失败原因，不能留空白气泡。
          if (event.data.status === "FAILED") {
            messages.value[index].content =
              messages.value[index].content || "这次没有拿到模型返回（连接或输出流失败），请重试一次。";
            messages.value[index].failed = true;
          }
          emit("trace", event.data.trace || []);
          emit("answered", event.data);
        }
      },
    );
    if (active()) {
      status.value =
        messages.value[index].taskStatus === "AWAITING_CONFIRMATION"
          ? "产物已保存，请在 Agent 产物中审核。"
          : "回答已保存。";
    }
  } catch (error) {
    if (active()) {
      status.value = error.message;
      messages.value[index].content ||= error.message;
      messages.value[index].failed = true;
    }
  } finally {
    if (mounted && watchdog.finish(requestId)) {
      busy.value = false;
      if (streamId === requestId) streamId = null;
      await refreshProject().catch(() => {});
      await scroll();
    }
  }
}
function openSource(source) {
  if (/^https?:\/\//.test(source)) window.audioAgent.openSource(source);
}
function stop() {
  if (streamId) window.audioAgent.cancel(streamId);
}
onMounted(async () => {
  try {
    const sessions = (
      await api("/api/v1/projects/" + state.project.id + "/sessions")
    ).sessions;
    state.sessions = sessions;
    if (sessions.length) await selectSession(sessions[0].id);
  } catch (error) {
    status.value = error.message;
  }
});
onBeforeUnmount(() => {
  mounted = false;
  watchdog.dispose();
  if (streamId) window.audioAgent.cancel(streamId);
  streamId = null;
});
</script>
<template>
  <aside class="chat-pane">
    <div class="chat-heading">
      <div><span class="online-dot" /> Audio智能助手</div>
      <button
        class="icon"
        title="新对话"
        :disabled="busy"
        @click="selectSession(null)"
      >
        <Plus :size="17" />
      </button>
    </div>
    <select
      class="session-picker"
      :value="sessionId || ''"
      :disabled="busy"
      @change="selectSession($event.target.value)"
    >
      <option value="">新的对话</option>
      <option
        v-for="session in state.sessions"
        :key="session.id"
        :value="session.id"
      >
        {{ session.title }}
      </option>
    </select>
    <details class="chat-options"><summary>高级对话设置</summary><label class="muted">上下文策略
      <select v-model="memoryStrategy" class="session-picker" :disabled="busy">
        <option value="">后端默认</option>
        <option v-for="(label, id) in memoryLabels" :key="id" :value="id">{{ label }}</option>
      </select>
    </label></details>
    <div ref="list" class="messages">
      <div v-if="!messages.length" class="chat-welcome">
        <h3>聊聊这个内容</h3>
        <p>可以追问背景、解释难句，或者讨论下一段怎么制作。我会结合这个空间的资料回答。</p>
      </div>
      <details v-if="activity.length" class="agent-activity" :open="busy">
        <summary>
          {{ busy ? "正在思考…" : "本轮执行过程" }} · {{ activity.length }} 步
          <span v-if="busy && status" class="activity-now">{{ status }}</span>
        </summary>
        <div v-for="step in activity" :key="step.sequence" class="agent-step" :class="stepTone(step)">
          <span>{{ stepIcon(step) }} {{ step.label }} · {{ stepTool(step) }}</span>
          <small>{{ step.status }} · {{ step.detail }}</small>
          <RetrievalDecision :event="step" />
          <MemoryDecision :event="step" />
          <ModelBudgetDecision :event="step" />
        </div>
      </details>
      <article
        v-for="(item, index) in messages"
        :key="index"
        class="message"
        :class="item.role"
      >
        <small>{{ item.role === "user" ? "你" : "Audio智能助手" }}</small>
        <div class="message-content">{{ item.content || "正在思考…" }}</div>
        <button
          v-if="item.artifactCount"
          class="artifact-link"
          @click="emit('show-artifacts')"
        >
          查看 {{ item.artifactCount }} 个 Agent 产物
        </button>
        <small v-if="item.stats"
          >模型 {{ item.stats.model_calls }} 次 · 工具
          {{ item.stats.tool_calls }} 次 · 委派
          {{ item.stats.delegations }} 次</small
        >
        <details v-if="item.citations?.length" class="citations">
          <summary>{{ item.citations.length }} 条参考来源</summary>
          <div v-for="(source, i) in item.citations" :key="i">
            <button @click="openSource(source.source)">
              [{{ source.id?.startsWith("E") ? source.id : i + 1 }}]
              {{ source.title }}</button
            ><small>{{ source.source }}</small>
          </div>
        </details>
      </article>
    </div>
    <div class="chat-footer">
      <div class="chat-status">{{ status || "对话会保存在当前内容空间" }}</div>
      <form class="composer" @submit.prevent="send">
        <textarea
          v-model="input"
          maxlength="4000"
          rows="3"
          placeholder="追问背景、难句，或讨论内容…"
          :disabled="busy"
          @keydown.enter.exact.prevent="send"
        />
        <div class="composer-bottom">
          <small>Enter 发送 · Shift + Enter 换行</small
          ><button
            v-if="busy"
            class="icon"
            type="button"
            title="停止"
            @click="stop"
          >
            <Square :size="15" /></button
          ><button v-else class="send-button" :disabled="!input.trim()">
            <Send :size="17" />
          </button>
        </div>
      </form>
    </div>
  </aside>
</template>
