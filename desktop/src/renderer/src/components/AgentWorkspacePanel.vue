<script setup>
import { ref } from "vue";
import { boundReview, approvalPayload } from "../../../shared/approval-policy.js";
import ReasonDialog from "./ReasonDialog.vue";
const props = defineProps({ workspace: Object });
const emit = defineEmits(["trace"]);
const { state, api, safe, refreshProject, mediaUrl } = props.workspace;
const voice = ref("Microsoft Huihui Desktop");
const reasonRequest = ref(null);
const kinds = {
  LEARNING_PLAN: "学习计划",
  RESEARCH_REPORT: "研究报告",
  SCRIPT_DRAFT: "课程讲稿",
  REVIEW_REPORT: "复核报告",
};
const roles = {
  supervisor: "学习主控",
  study_planner: "学习规划",
  research_agent: "资料研究",
  script_writer: "课程编写",
  review_agent: "课程复核",
};
const statuses = {
  DRAFT: "待确认",
  READY: "音频已就绪",
  APPLIED: "计划已应用",
  GENERATING: "合成中",
  FAILED: "失败，可明确确认后重试",
  NEEDS_REVIEW: "中断，需人工核查",
  RUNNING: "执行中",
  SUCCEEDED: "已返回回答",
  AWAITING_CONFIRMATION: "等待产物确认",
  INTERRUPTED: "中断，未自动重放",
  CANCELLED: "已取消",
  LIMIT_REACHED: "达到运行上限",
  CONTEXT_LIMIT: "完整请求超过上下文预算",
  TOOL_TIMEOUT: "有状态工具超时，需核查结果",
  TOOL_RUNTIME_STOPPED: "工具运行资源停止，需核查结果",
  INSTANCE_LEASE_LOST: "运行节点租约失效，未自动重放",
};
function reviewOf(id) {
  const artifact = state.agentArtifacts.find(item => item.id === id);
  return artifact ? boundReview(artifact, state.agentArtifacts) : null;
}
async function confirmArtifact(artifact, action, overrideReason = "") {
  const review = reviewOf(artifact.id);
  if (action === "synthesize" && review?.approved !== true && !overrideReason) {
    reasonRequest.value = { artifact, action, kind: "override", title: "人工覆盖复核", message: "没有当前内容的通过复核。理由将记录在审计中；确认后仍需核对合成操作。" };
    return;
  }
  const text =
    action === "synthesize"
      ? (review?.approved === false ? "复核发现问题。" : "") +
        "确认使用当前讲稿合成本机音频？"
      : "确认将该计划中的知识点追加到学习进度？已有进度会保留。";
  if (!confirm(text)) return;
  await safe(async () => {
    await api(
      "/api/v1/projects/" +
        state.project.id +
        "/agent/artifacts/" +
        artifact.id +
        "/" +
        action,
      "POST",
      approvalPayload(artifact, review, voice.value, overrideReason),
    );
    await refreshProject();
    state.notice =
      action === "synthesize"
        ? "音频已生成，可在这里或音频工作室播放。"
        : "学习知识点已追加。";
  });
}
function runIsActive(artifact) {
  return state.agentRuns.some(run => run.id === artifact.run_id && run.status === "RUNNING");
}
async function recoverArtifact(artifact) {
  if (!confirm("核对原合成编号的现存WAV并恢复完成状态？不会重新合成或删除文件。")) return;
  await safe(async () => {
    await api(`/api/v1/projects/${state.project.id}/agent/artifacts/${artifact.id}/recover-synthesis`, "POST", {
      confirmed: true, expected_content_hash: artifact.content_hash, expected_audio_id: artifact.audio_id,
      existing_url: `/media/${artifact.audio_id}.wav`,
    });
    await refreshProject(); state.notice = "原音频已核验并恢复，没有重新合成。";
  });
}
async function resetAttempt(artifact, reason = "") {
  if (!reason) {
    reasonRequest.value = { artifact, kind: "reset", title: "人工重置合成尝试", message: "保留旧文件和尝试记录。重置只回到草稿，不会立即合成；随后须另行确认。" };
    return;
  }
  await safe(async () => {
    await api(`/api/v1/projects/${state.project.id}/agent/artifacts/${artifact.id}/retry-synthesis`, "POST", {
      confirmed: true, expected_content_hash: artifact.content_hash, expected_audio_id: artifact.audio_id, reason,
    });
    await refreshProject(); state.notice = "已保留旧尝试并重置草稿；尚未重新合成，请另行审核确认。";
  });
}
async function submitReason(reason) {
  const request = reasonRequest.value; reasonRequest.value = null;
  if (!request) return;
  if (request.kind === "reset") await resetAttempt(request.artifact, reason);
  else await confirmArtifact(request.artifact, request.action, reason);
}
async function openRun(run) {
  await safe(async () => {
    const result = await api(
      "/api/v1/projects/" + state.project.id + "/agent/runs/" + run.id,
    );
    emit("trace", result.trace || []);
  });
}
</script>
<template>
  <section class="panel">
    <ReasonDialog :open="!!reasonRequest" :title="reasonRequest?.title" :message="reasonRequest?.message"
      @cancel="reasonRequest = null" @confirm="submitReason" />
    <div class="panel-heading">
      <div>
        <div class="eyebrow">AGENT WORKSPACE</div>
        <h2>Agent 产物与任务</h2>
      </div>
      <span class="badge">{{
        state.agentRuntime === "native-function-calling"
          ? "模型自主工具调用"
          : "当前为基础检索流程"
      }}</span>
    </div>
    <p class="muted">
      在右侧输入“根据项目资料，生成一份适合初学者的 5
      分钟课程，并复核讲稿”。主控会按需委派规划、研究、编写与复核 Agent。
    </p>
    <p v-if="state.agentRuntime !== 'native-function-calling'" class="muted">
      配置模型密钥并启用 Agent 后，才会运行原生 Function Calling 和子 Agent
      委派。
    </p>
    <details class="card">
      <summary>已注册的 Agent 与能力</summary>
      <div
        v-for="agent in state.agentCapabilities"
        :key="agent.id"
        class="agent-capability"
      >
        <strong>{{ agent.title }}</strong
        ><small>{{ agent.tools.join(" · ") }}</small>
      </div>
    </details>
    <div class="row">
      <label
        >讲稿合成音色<select v-model="voice">
          <option>Microsoft Huihui Desktop</option>
          <option>Microsoft Kangkang Desktop</option>
          <option>Microsoft Yaoyao Desktop</option>
          <option value="">系统默认</option>
        </select></label
      >
    </div>
    <article
      v-for="artifact in state.agentArtifacts"
      :key="artifact.id"
      class="card"
    >
      <div class="row spaced">
        <h3>
          {{ kinds[artifact.kind] || artifact.kind }} ·
          {{ roles[artifact.agent_id] || artifact.agent_id }}
        </h3>
        <span class="badge">{{
          statuses[artifact.status] || artifact.status
        }}</span>
      </div>
      <small class="muted">产物 {{ artifact.id }}</small>
      <details>
        <summary>查看完整产物</summary>
        <pre>{{ artifact.content }}</pre>
      </details>
      <p
        v-if="artifact.kind === 'SCRIPT_DRAFT' && reviewOf(artifact.id)"
        :class="{ danger: !reviewOf(artifact.id).approved }"
      >
        {{
          reviewOf(artifact.id).approved
            ? "复核建议：通过"
            : "复核建议：需要修改"
        }}
        · {{ reviewOf(artifact.id).reason }}
      </p>
      <div class="row">
        <button
          v-if="artifact.kind === 'SCRIPT_DRAFT' && artifact.status === 'DRAFT'"
          class="primary"
          :disabled="state.busy || runIsActive(artifact)"
          @click="confirmArtifact(artifact, 'synthesize')"
        >
          确认并合成音频
        </button>
        <button
          v-if="
            artifact.kind === 'LEARNING_PLAN' && artifact.status !== 'APPLIED'
          "
          class="primary"
          :disabled="state.busy || runIsActive(artifact)"
          @click="confirmArtifact(artifact, 'apply-plan')"
        >
          确认并应用学习计划
        </button>
        <button v-if="artifact.kind === 'SCRIPT_DRAFT' && artifact.status === 'NEEDS_REVIEW' && artifact.audio_id"
          :disabled="state.busy || runIsActive(artifact)" @click="recoverArtifact(artifact)">核验原音频并恢复</button>
        <button v-if="artifact.kind === 'SCRIPT_DRAFT' && artifact.status === 'NEEDS_REVIEW'"
          :disabled="state.busy || runIsActive(artifact)" @click="resetAttempt(artifact)">人工重置合成尝试</button>
      </div>
      <details v-if="artifact.audit?.length"><summary>查看确认与恢复审计</summary><pre>{{ JSON.stringify(artifact.audit, null, 2) }}</pre></details>
      <audio
        v-if="artifact.audio_url"
        controls
        preload="none"
        :src="mediaUrl(artifact.audio_url)"
      />
    </article>
    <p v-if="!state.agentArtifacts.length" class="empty">
      多 Agent 生成的计划、研究报告、讲稿和复核结果会保存在这里。
    </p>
    <h3>任务历史</h3>
    <article v-for="run in state.agentRuns" :key="run.id" class="card">
      <div class="row spaced">
        <strong>{{ run.goal }}</strong
        ><span class="badge">{{ statuses[run.status] || run.status }}</span>
      </div>
      <p class="muted">{{ run.summary }}</p>
      <details v-if="run.task_specification"><summary>最低完成规格</summary><pre>{{ JSON.stringify(run.task_specification, null, 2) }}</pre></details>
      <button @click="openRun(run)">查看工具轨迹</button>
    </article>
  </section>
</template>
