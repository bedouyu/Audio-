<script setup>
import { computed, onMounted, ref } from "vue";
import { boundReview, approvalPayload, REVIEW_CHECKS } from "../../../shared/approval-policy.js";
import ReasonDialog from "./ReasonDialog.vue";
import ScriptDiff from "./ScriptDiff.vue";
const props = defineProps({ workspace: Object });
const emit = defineEmits(["trace"]);
const { state, api, safe, refreshProject, mediaUrl } = props.workspace;
const voice = ref("");
const voices = ref([]);
const voiceNotice = ref("");
const reasonRequest = ref(null);
const kinds = {
  LEARNING_PLAN: "学习计划",
  RESEARCH_REPORT: "研究报告",
  SCRIPT_DRAFT: "音频讲稿",
  REVIEW_REPORT: "复核报告",
};
const roles = {
  supervisor: "主控",
  study_planner: "学习规划",
  research_agent: "资料研究",
  script_writer: "讲稿编写",
  review_agent: "讲稿复核",
};
const statuses = {
  DRAFT: "待确认",
  SUPERSEDED: "已被新稿替代",
  CONSUMED: "已用于音频制作",
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
const reviews = computed(() => Object.fromEntries(state.agentArtifacts.filter(item => item.kind === "SCRIPT_DRAFT")
  .map(artifact => [artifact.id, boundReview(artifact, state.agentArtifacts)])));
function reviewOf(id) { return reviews.value[id] || null; }
function artifactOrder(artifact) {
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(String(artifact.created_at || ""));
  return match ? match[1] + "." + (match[2] || "").padEnd(9, "0") : "";
}
const previousScripts = computed(() => {
  const scripts = state.agentArtifacts.filter(item => item.kind === "SCRIPT_DRAFT");
  return Object.fromEntries(scripts.map(artifact => {
    const candidates = scripts.filter(previous => previous.id !== artifact.id && previous.run_id === artifact.run_id
      && previous.status === "SUPERSEDED" && artifactOrder(previous) && artifactOrder(previous) <= artifactOrder(artifact))
      .sort((first, second) => artifactOrder(second).localeCompare(artifactOrder(first)) || String(second.id).localeCompare(String(first.id)));
    const explicit = candidates.find(previous => artifact.input_ids?.includes(previous.id));
    return [artifact.id, explicit || candidates[0] || null];
  }));
});
function previousOf(id) { return previousScripts.value[id] || null; }
onMounted(async () => {
  try {
    const inventory = await api("/api/v1/audio/voices");
    voices.value = (inventory.voices || []).filter(item => item?.name && item.enabled !== false);
  } catch { voiceNotice.value = "暂未读取到音色列表；合成时将按稿件语言自动选择。"; }
});
async function confirmArtifact(artifact, action, overrideReason = "", projectId = state.project?.id) {
  const current = state.agentArtifacts.find(item => item.id === artifact.id);
  if (projectId !== state.project?.id || !current || current.content_hash !== artifact.content_hash || current.status !== "DRAFT") {
    state.notice = "稿件或当前空间已变化，请重新打开当前稿件审核。";
    return;
  }
  const review = reviewOf(artifact.id);
  if (action === "synthesize" && review?.approved !== true && !overrideReason) {
    reasonRequest.value = { artifact, action, projectId, kind: "override", title: "人工覆盖复核", message: "当前稿件没有完整、通过的最新复核。请先阅读检查项和问题；如果仍决定合成，填写的理由将记录在审核历史中。" };
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
        projectId +
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
  else await confirmArtifact(request.artifact, request.action, reason, request.projectId);
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
          <option value="">按稿件语言自动选择</option>
          <option v-for="item in voices" :key="item.name" :value="item.name">{{ item.display_name || item.name }}</option>
        </select></label
      >
    </div>
    <p v-if="voiceNotice" class="muted">{{ voiceNotice }}</p>
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
      <section v-if="artifact.kind === 'SCRIPT_DRAFT'" class="script-preview" aria-label="讲稿正文与版本对比">
        <template v-if="previousOf(artifact.id)">
          <strong>本次修订与上一稿的差异</strong>
          <p class="muted">绿色是新增，红色删除线是删除；确认合成时使用本次修订的完整正文。</p>
          <ScriptDiff :before="previousOf(artifact.id).content" :after="artifact.content" />
        </template>
        <template v-else>
          <strong>首版讲稿正文</strong>
          <pre class="script-original">{{ artifact.content }}</pre>
        </template>
        <small v-if="artifact.status === 'DRAFT'" class="muted">请结合下方最新复核审核当前版本；确认后才会执行这份稿件的合成。</small>
      </section>
      <details v-if="artifact.kind !== 'SCRIPT_DRAFT' || previousOf(artifact.id)">
        <summary>查看完整产物</summary>
        <pre>{{ artifact.content }}</pre>
      </details>
      <section v-if="artifact.kind === 'SCRIPT_DRAFT'" class="review-summary" aria-label="当前稿件复核结果">
        <template v-if="reviewOf(artifact.id)">
          <strong :class="reviewOf(artifact.id).approved ? 'review-passed' : 'danger'">
            {{ reviewOf(artifact.id).approved ? "最新复核通过" : reviewOf(artifact.id).valid ? "最新复核未通过" : "最新复核不完整，不能据此批准" }}
          </strong>
          <p v-if="typeof reviewOf(artifact.id).reason === 'string'">{{ reviewOf(artifact.id).reason }}</p>
          <ul class="review-checks">
            <li v-for="(label, key) in REVIEW_CHECKS" :key="key"
              :class="{ 'review-passed': reviewOf(artifact.id).checks?.[key] === true, danger: reviewOf(artifact.id).checks?.[key] === false }">
              {{ label }}：{{ reviewOf(artifact.id).checks?.[key] === true ? '通过' : reviewOf(artifact.id).checks?.[key] === false ? '未通过' : '未检查' }}
            </li>
          </ul>
          <ul v-if="Array.isArray(reviewOf(artifact.id).issues) && reviewOf(artifact.id).issues.length" class="review-issues danger">
            <li v-for="(issue, index) in reviewOf(artifact.id).issues" :key="index">{{ issue }}</li>
          </ul>
          <ul v-if="reviewOf(artifact.id).validationProblems.length" class="review-issues danger">
            <li v-for="issue in reviewOf(artifact.id).validationProblems" :key="issue">{{ issue }}</li>
          </ul>
          <small class="muted">只采用绑定这份稿件正文的最新复核。报告 {{ reviewOf(artifact.id).id }}</small>
        </template>
        <p v-else class="muted">当前稿件尚无匹配的复核报告。请让助手复核这份稿件，再决定是否制作音频。</p>
      </section>
      <div class="row">
        <button
          v-if="artifact.kind === 'SCRIPT_DRAFT' && artifact.status === 'DRAFT'"
          class="primary"
          :disabled="state.busy || runIsActive(artifact)"
          @click="confirmArtifact(artifact, 'synthesize')"
        >
          {{ reviewOf(artifact.id)?.approved ? '确认并合成音频' : '人工审核后合成' }}
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
<style scoped>
.review-summary { margin: 14px 0; padding: 14px; border: 1px solid var(--border, #dce4df); border-radius: 12px; background: var(--surface-soft, #f7f9f7); }
.review-summary p { margin: 8px 0; }
.review-checks { display: flex; flex-wrap: wrap; gap: 8px 16px; list-style: none; padding: 0; margin: 10px 0; font-size: 13px; }
.review-issues { margin: 10px 0; padding-left: 22px; }
.review-passed { color: #187445; }
.script-preview { display: grid; gap: 8px; margin: 12px 0; }
.script-preview p { margin: 0; font-size: 13px; }
.script-original { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 48vh; overflow-y: auto; padding: 14px; border: 1px solid var(--border, #dce4df); border-radius: 8px; line-height: 1.9; }
</style>
