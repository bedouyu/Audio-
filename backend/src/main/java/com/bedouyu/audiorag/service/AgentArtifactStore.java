package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.core.guard.ScriptReviewPolicy;
import com.bedouyu.audiorag.model.entity.AgentArtifact;
import com.bedouyu.audiorag.model.entity.AgentRunRecord;
import com.bedouyu.audiorag.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class AgentArtifactStore {
    private final AgentArtifactRepository artifacts;
    private final AudioRagRepository repository;
    private final ObjectMapper json;
    private final LearningPlanService learning;
    private final AgentRunRecordRepository runs;
    public AgentArtifactStore(AgentArtifactRepository artifacts, AudioRagRepository repository,
                              ObjectMapper json, LearningPlanService learning, AgentRunRecordRepository runs) {
        this.artifacts = artifacts; this.repository = repository; this.json = json;
        this.learning = learning; this.runs = runs;
    }
    @Transactional
    public Map<String, Object> save(String run, long user, long project, String agent,
                                    String kind, String content, List<String> inputIds,
                                    List<Map<String, Object>> citations) {
        requireProject(user, project);
        var owner = lockRun(user, project, run);
        if (!"RUNNING".equals(owner.getStatus())) throw conflict("任务已结束，不能追加产物");
        // Locking read sees the preceding writer after waiting for the run lock,
        // including under MySQL REPEATABLE READ where an earlier ownership read may hold an old snapshot.
        var previousOutputs = artifacts.lockedForRun(run);
        // 所有追加都持有 run 锁。使用数据库可保留的毫秒精度并严格递增，
        // 避免 Instant 纳秒被截断后“最新复核”退化为随机 UUID 的排序。
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var latest = previousOutputs.stream().map(AgentArtifact::getCreatedAt).max(Comparator.naturalOrder());
        if (latest.isPresent() && !createdAt.isAfter(latest.get()))
            createdAt = latest.get().truncatedTo(ChronoUnit.MILLIS).plusMillis(1);
        // 复核未通过时主控会重新委派写手；新讲稿必须取代同任务的旧草稿，
        // 否则用户会看到两份"待确认"讲稿，也说不清该确认哪一份。
        // 只对讲稿生效：学习计划允许在同一任务里各自确认，研究报告与复核报告是中间证据。
        if ("SCRIPT_DRAFT".equals(kind))
            for (var previous : previousOutputs)
                if (kind.equals(previous.getKind()) && "DRAFT".equals(previous.getStatus()))
                    previous.supersede();
        var artifact = artifacts.saveAndFlush(new AgentArtifact(UUID.randomUUID().toString(), run, user,
                project, agent, kind, content, json.writeValueAsString(inputIds), json.writeValueAsString(citations), createdAt));
        return view(artifact);
    }
    @Transactional(readOnly = true)
    public Map<String, Object> get(long user, long project, String id) { return view(require(user, project, id, false)); }
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(long user, long project) {
        requireProject(user, project);
        return artifacts.findTop30ByUserIdAndProjectIdOrderByCreatedAtDesc(user, project).stream().map(this::view).toList();
    }
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forRun(long user, long project, String run) {
        requireProject(user, project);
        return artifacts.findByRunIdOrderByCreatedAtAsc(run).stream()
                .filter(a -> a.getUserId() == user && a.getProjectId() == project).map(this::view).toList();
    }
    /**
     * 音频队列直接消费讲稿草稿并合成：草稿不再等待用户确认。
     * 重复消费按成功处理（幂等）；已合成或待审核的产物不能被静默覆盖。
     */
    @Transactional
    public Map<String, Object> consume(long user, long project, String id) {
        var artifact = require(user, project, id, true);
        if (!"SCRIPT_DRAFT".equals(artifact.getKind())) throw badRequest("只有课程讲稿可以被音频队列消费");
        if ("CONSUMED".equals(artifact.getStatus())) return view(artifact);
        if (!"DRAFT".equals(artifact.getStatus()))
            throw conflict("讲稿状态为 " + artifact.getStatus() + "；不能按音频队列消费处理");
        requireFinishedRun(artifact);
        var outputs = artifacts.lockedForRun(artifact.getRunId()).stream().map(this::view).toList();
        var review = ScriptReviewPolicy.approvedReviewFor(outputs, view(artifact), json)
                .orElseThrow(() -> conflict("当前讲稿尚无绑定同一内容版本的已通过复核报告，不能直接生成音频"));
        artifact.consume();
        var audit = actionAudit(artifact, "CONSUME", user);
        audit.put("review_id", review.get("id"));
        audit.put("review_content_hash", Hashing.sha256(String.valueOf(review.get("content"))));
        audit.put("review_bound_content_hash", Hashing.sha256(artifact.getContent()));
        appendAudit(artifact, audit);
        settleRun(artifact);
        return view(artifact);
    }
    @Transactional
    public Map<String, Object> beginSynthesis(long user, long project, String id) {
        return beginSynthesis(user, project, id, null, null, false, null);
    }
    @Transactional
    public Map<String, Object> beginSynthesis(long user, long project, String id, String expectedContentHash,
                                              String reviewId, boolean overrideReview, String overrideReason) {
        var artifact = require(user, project, id, true);
        if (!"SCRIPT_DRAFT".equals(artifact.getKind())) throw badRequest("只有课程讲稿可合成音频");
        // 音频请求已经把这份正文消费并合成过，这里给出明确原因而不是笼统的校验失败。
        if ("CONSUMED".equals(artifact.getStatus()))
            throw conflict("该讲稿已被直接生成的音频消费并合成，不能再次确认合成");
        requireHash(artifact, expectedContentHash, "READY".equals(artifact.getStatus()));
        if ("READY".equals(artifact.getStatus())) return view(artifact);
        requireFinishedRun(artifact);
        if (!"DRAFT".equals(artifact.getStatus()))
            throw conflict("讲稿状态为 " + artifact.getStatus() + "；不能重复合成，中断产物需要人工恢复确认");
        Map<String, Object> audit = actionAudit(artifact, "SYNTHESIZE", user);
        audit.put("override_review", overrideReview);
        if (overrideReview) {
            String reason = overrideReason == null ? "" : overrideReason.strip();
            if (reason.length() < 10 || reason.length() > 500) throw badRequest("跳过审核的理由必须为 10 至 500 字");
            audit.put("override_reason", reason);
        } else {
            var review = requireReview(user, project, artifact, reviewId);
            audit.put("review_id", review.getId());
            audit.put("review_content_hash", Hashing.sha256(review.getContent()));
            audit.put("review_approved", true);
            audit.put("review_bound_content_hash", Hashing.sha256(artifact.getContent()));
        }
        artifact.generating(UUID.randomUUID().toString());
        audit.put("audio_id", artifact.getAudioId()); appendAudit(artifact, audit);
        artifacts.flush(); return view(artifact);
    }
    @Transactional
    public Map<String, Object> completeSynthesis(long user, long project, String id, String url) {
        var artifact = require(user, project, id, true);
        if (!"GENERATING".equals(artifact.getStatus()) || !audioUrl(artifact).equals(url))
            throw conflict("合成状态或音频编号不匹配");
        return commitAudio(artifact, project, id, url);
    }
    @Transactional
    public Map<String, Object> recoverSynthesis(long user, long project, String id, String expectedContentHash,
                                               String expectedAudioId, String existingUrl) {
        var artifact = require(user, project, id, true);
        if (!"SCRIPT_DRAFT".equals(artifact.getKind())) throw badRequest("只有课程讲稿可恢复音频");
        requireHash(artifact, expectedContentHash, false);
        if (!Objects.equals(artifact.getAudioId(), expectedAudioId) || expectedAudioId == null
                || !audioUrl(artifact).equals(existingUrl)) throw conflict("恢复音频编号或地址与原合成不匹配");
        if ("READY".equals(artifact.getStatus())) return view(artifact);
        requireFinishedRun(artifact);
        if (!"NEEDS_REVIEW".equals(artifact.getStatus())) throw conflict("只有中断且待人工复核的合成可恢复");
        var audit = actionAudit(artifact, "RECOVER_SYNTHESIS", user);
        audit.put("audio_id", expectedAudioId); audit.put("audio_url", existingUrl);
        appendAudit(artifact, audit);
        return commitAudio(artifact, project, id, existingUrl);
    }
    @Transactional
    public Map<String, Object> resetSynthesisAttempt(long user, long project, String id, String expectedContentHash,
                                                     String expectedAudioId, String reason) {
        var artifact = require(user, project, id, true);
        if (!"SCRIPT_DRAFT".equals(artifact.getKind())) throw badRequest("只有课程讲稿可重置合成尝试");
        requireHash(artifact, expectedContentHash, false);
        requireFinishedRun(artifact);
        if (!"NEEDS_REVIEW".equals(artifact.getStatus())) throw conflict("只有待人工复核的中断合成可重置");
        if (expectedAudioId == null || !expectedAudioId.equals(artifact.getAudioId()))
            throw conflict("重置必须绑定原合成编号");
        String normalizedReason = reason == null ? "" : reason.strip();
        if (normalizedReason.length() < 10 || normalizedReason.length() > 500)
            throw badRequest("重新尝试的人工确认理由必须为 10 至 500 字");
        var audit = actionAudit(artifact, "RESET_SYNTHESIS_ATTEMPT", user);
        audit.put("reason", normalizedReason); audit.put("old_audio_id", artifact.getAudioId());
        audit.put("old_audio_url", artifact.getAudioUrl() == null ? audioUrl(artifact) : artifact.getAudioUrl());
        audit.put("old_recorded_audio_url", artifact.getAudioUrl()); audit.put("old_expected_audio_url", audioUrl(artifact));
        appendAudit(artifact, audit);
        artifact.resetSynthesisAttempt(); artifacts.flush();
        return view(artifact);
    }
    private Map<String, Object> commitAudio(AgentArtifact artifact, long project, String id, String url) {
        repository.addProjectRecord(project, "lesson", "Agent 课程音频", artifact.getContent(),
                Map.of("lesson_id", artifact.getAudioId(), "audio_url", url, "artifact_id", id,
                        "run_id", artifact.getRunId(), "continuous", false));
        artifact.ready(url); artifacts.flush(); settleRun(artifact); return view(artifact);
    }
    @Transactional
    public void failSynthesis(long user, long project, String id) {
        failSynthesis(user, project, id, null);
    }
    @Transactional
    public void failSynthesis(long user, long project, String id, String expectedAudioId) {
        var artifact = require(user, project, id, true);
        // A late response from a retained old attempt cannot interrupt a separately confirmed new attempt.
        if (expectedAudioId != null && !expectedAudioId.equals(artifact.getAudioId())) return;
        if ("GENERATING".equals(artifact.getStatus())) {
            artifact.interrupted();
            appendAudit(artifact, actionAudit(artifact, "SYNTHESIS_NEEDS_REVIEW", user));
        }
    }
    @Transactional
    public Map<String, Object> applied(long user, long project, String id) { return applyPlan(user, project, id); }
    @Transactional
    public Map<String, Object> applyPlan(long user, long project, String id) { return applyPlan(user, project, id, null); }
    @Transactional
    public Map<String, Object> applyPlan(long user, long project, String id, String expectedContentHash) {
        var artifact = require(user, project, id, true);
        if (!"LEARNING_PLAN".equals(artifact.getKind())) throw badRequest("此产物不是学习计划");
        requireHash(artifact, expectedContentHash, "APPLIED".equals(artifact.getStatus()));
        if ("APPLIED".equals(artifact.getStatus())) return view(artifact);
        requireFinishedRun(artifact);
        if (!"DRAFT".equals(artifact.getStatus())) throw conflict("计划状态不允许应用");
        List<String> titles = new ArrayList<>();
        for (var point : json.readTree(artifact.getContent()).path("points")) titles.add(point.path("title").asText());
        int added = learning.appendConfirmedPlan(project, titles);
        appendAudit(artifact, actionAudit(artifact, "APPLY_PLAN", user)); artifact.applied();
        artifacts.flush(); settleRun(artifact);
        Map<String, Object> result = view(artifact); result.put("created_points", added); return result;
    }
    @EventListener(ApplicationReadyEvent.class)
    @Transactional public void markInterruptedSynthesis() {
        // Candidates are only hints. Recheck under the same run -> artifact lock order as confirmation.
        for (var id : artifacts.findIdsByStatus("GENERATING")) {
            var runId = artifacts.findRunId(id);
            if (runId.isEmpty() || runs.locked(runId.get()).isEmpty()) continue;
            artifacts.locked(id).filter(a -> "GENERATING".equals(a.getStatus()))
                    .ifPresent(artifact -> {
                        artifact.interrupted();
                        appendAudit(artifact, actionAudit(artifact, "PROCESS_INTERRUPTED", artifact.getUserId()));
                    });
        }
    }
    @Transactional public void deleteProject(long id) {
        for (var run : runs.findByProjectIdOrderByIdAsc(id)) {
            runs.locked(run.getId()); artifacts.lockedForRun(run.getId());
        }
        artifacts.deleteByProjectId(id);
    }
    @SuppressWarnings("unchecked")
    private void settleRun(AgentArtifact current) {
        // require(..., true) already holds this run's lock; failure/interruption terminal states stay intact.
        var run = runs.locked(current.getRunId()).orElseThrow(this::missing);
        if (!"AWAITING_CONFIRMATION".equals(run.getStatus())) return;
        var outputs = artifacts.lockedForRun(current.getRunId());
        boolean pending = outputs.stream().anyMatch(a -> Set.of("SCRIPT_DRAFT", "LEARNING_PLAN").contains(a.getKind())
                && !Set.of("READY", "APPLIED", "CONSUMED", "SUPERSEDED").contains(a.getStatus()));
        if (!pending) {
            Map<String, Object> result = run.getResult() == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(json.readValue(run.getResult(), Map.class));
            result.put("status", "SUCCEEDED"); result.put("artifacts", outputs.stream().map(this::view).toList());
            run.finish("SUCCEEDED", "待确认的计划与讲稿已由用户确认执行", json.writeValueAsString(result));
        }
    }
    private AgentArtifact require(long user, long project, String id, boolean lock) {
        requireProject(user, project);
        if (lock) {
            String runId = artifacts.findRunId(id).orElseThrow(this::missing);
            lockRun(user, project, runId);
        }
        var artifact = (lock ? artifacts.locked(id) : artifacts.findById(id)).orElseThrow(this::missing);
        if (artifact.getUserId() != user || artifact.getProjectId() != project)
            throw new ApiException(HttpStatus.NOT_FOUND, "Agent 产物不属于当前项目");
        return artifact;
    }
    private AgentRunRecord lockRun(long user, long project, String id) {
        var run = runs.locked(id).orElseThrow(this::missing);
        if (run.getUserId() != user || run.getProjectId() != project) throw missing();
        return run;
    }
    private void requireFinishedRun(AgentArtifact artifact) {
        if ("RUNNING".equals(runs.locked(artifact.getRunId()).orElseThrow(this::missing).getStatus()))
            throw conflict("任务仍在运行，请等待任务完成后确认产物");
    }
    private void requireHash(AgentArtifact artifact, String expected, boolean terminalReuse) {
        if (terminalReuse && (expected == null || expected.isBlank())) return;
        if (expected == null || !Hashing.sha256(artifact.getContent()).equals(expected))
            throw conflict("确认必须绑定当前产物的 content_hash，请重新打开产物审核");
    }
    private AgentArtifact requireReview(long user, long project, AgentArtifact target, String reviewId) {
        if (reviewId == null || reviewId.isBlank()) throw conflict("合成讲稿需要针对当前内容的已通过审核报告");
        // Target run is locked, so all immutable reports in this run are stable while we validate the binding.
        var review = artifacts.findById(reviewId).orElseThrow(this::missing);
        if (review.getUserId() != user || review.getProjectId() != project || !review.getRunId().equals(target.getRunId())
                || !"REVIEW_REPORT".equals(review.getKind())) throw conflict("审核报告不属于该讲稿任务");
        var outputs = artifacts.lockedForRun(target.getRunId()).stream().map(this::view).toList();
        var current = ScriptReviewPolicy.approvedReviewFor(outputs, view(target), json)
                .orElseThrow(() -> conflict("当前讲稿的最新复核未通过或不完整，请重新审核，或明确说明人工覆盖理由"));
        if (!reviewId.equals(current.get("id")))
            throw conflict("复核报告已更新，请刷新后根据最新复核确认讲稿");
        return review;
    }
    private Map<String, Object> actionAudit(AgentArtifact artifact, String action, long user) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("action", action); value.put("user_id", user);
        value.put("confirmed", Set.of("SYNTHESIZE", "RECOVER_SYNTHESIS", "RESET_SYNTHESIS_ATTEMPT", "APPLY_PLAN").contains(action));
        value.put("content_hash", Hashing.sha256(artifact.getContent()));
        value.put("artifact_version", artifact.getVersion()); value.put("recorded_at", Instant.now().toString());
        return value;
    }
    @SuppressWarnings("unchecked")
    private void appendAudit(AgentArtifact artifact, Map<String, Object> entry) {
        List<Map<String, Object>> audit = artifact.getApprovalJson() == null ? new ArrayList<>()
                : new ArrayList<>(json.readValue(artifact.getApprovalJson(), List.class));
        audit.add(entry); artifact.audit(json.writeValueAsString(audit));
    }
    private String audioUrl(AgentArtifact artifact) { return "/media/" + artifact.getAudioId() + ".wav"; }
    private void requireProject(long user, long project) {
        if (!repository.ownsProject(user, project)) throw new ApiException(HttpStatus.NOT_FOUND, "项目不存在");
    }
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "Agent 产物不存在或不属于当前项目"); }
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    private ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private Map<String, Object> view(AgentArtifact artifact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", artifact.getId()); value.put("run_id", artifact.getRunId());
        value.put("agent_id", artifact.getAgentId()); value.put("kind", artifact.getKind());
        value.put("content", artifact.getContent()); value.put("status", artifact.getStatus());
        value.put("content_hash", Hashing.sha256(artifact.getContent())); value.put("version", artifact.getVersion());
        value.put("input_ids", json.readValue(artifact.getInputIds(), List.class));
        value.put("citations", json.readValue(artifact.getEvidenceJson(), List.class));
        value.put("audio_id", artifact.getAudioId()); value.put("audio_url", artifact.getAudioUrl());
        value.put("audit", artifact.getApprovalJson() == null ? List.of() : json.readValue(artifact.getApprovalJson(), List.class));
        value.put("created_at", artifact.getCreatedAt()); return value;
    }
}
