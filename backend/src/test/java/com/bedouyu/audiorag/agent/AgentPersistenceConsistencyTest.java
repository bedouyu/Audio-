package com.bedouyu.audiorag.agent;

import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.repository.*;
import com.bedouyu.audiorag.service.*;
import com.bedouyu.audiorag.rag.*;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.tools.storage.MinioStorageService;
import com.bedouyu.audiorag.tools.research.WebSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:agent_consistency;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "audiorag.llm.api-key="
})
class AgentPersistenceConsistencyTest {
    @MockitoSpyBean AudioRagRepository repository;
    @Autowired AgentArtifactStore artifacts;
    @Autowired AgentRunStore runs;
    @Autowired AgentRunRecordRepository runRecords;
    @Autowired AgentArtifactApprovalService approval;
    @Autowired LearningPlanService learning;
    @Autowired ObjectMapper json;
    @MockitoBean QdrantService qdrant;
    @MockitoBean OnnxEmbeddingService embeddings;
    @MockitoBean MinioStorageService minio;
    @MockitoBean SpeechService speech;
    @MockitoBean WebSearchService web;
    @MockitoBean ContinuousAudioService continuous;

    @Test
    void confirmationRequiresFinalRunAndExactContentAndLegacyCannotBypassIt() {
        var f = fixture();
        var plan = plan(f);
        assertConflict(() -> approval.applyPlan(f.user, f.project, id(plan), true));
        assertConflict(() -> approval.applyPlan(f.user, f.project, id(plan), true, hash(plan)));
        assertThat(learning.list(f.project)).isEmpty();
        finish(f);
        assertConflict(() -> approval.applyPlan(f.user, f.project, id(plan), true, Hashing.sha256("different")));
        approval.applyPlan(f.user, f.project, id(plan), true, hash(plan));
        approval.applyPlan(f.user, f.project, id(plan), true);
        assertThat(learning.list(f.project)).hasSize(3);
        assertThat(status(f)).isEqualTo("SUCCEEDED");
    }

    @Test
    void parallelConfirmationsAndLateFinishUseFreshArtifactsAndConverge() throws Exception {
        var f = fixture();
        var first = plan(f, "第一组");
        var second = plan(f, "第二组");
        finish(f);
        var stale = Map.<String, Object>of("answer", "stored", "artifacts", List.of(first, second));
        var outcomes = parallel(
                () -> approval.applyPlan(f.user, f.project, id(first), true, hash(first)),
                () -> approval.applyPlan(f.user, f.project, id(second), true, hash(second)));
        assertThat(outcomes).allMatch(value -> value instanceof Map);
        assertThat(status(f)).isEqualTo("SUCCEEDED");
        var race = parallel(
                () -> approval.applyPlan(f.user, f.project, id(second), true, hash(second)),
                () -> { runs.finish(f.run, "AWAITING_CONFIRMATION", "old snapshot", stale); return true; });
        assertThat(race).noneMatch(value -> value instanceof Throwable);
        assertThat(status(f)).isEqualTo("SUCCEEDED");
        assertThat(learning.list(f.project)).hasSize(6);
        var result = (Map<?, ?>) runs.get(f.user, f.project, f.run).get("result");
        assertThat((List<?>) result.get("artifacts")).allSatisfy(value ->
                assertThat(((Map<?, ?>) value).get("status")).isEqualTo("APPLIED"));
    }

    @Test
    void duplicatePlanConfirmationProducesOneAuditAndOneSetOfPoints() throws Exception {
        var f = fixture();
        var plan = plan(f);
        finish(f);
        assertThat(parallel(
                () -> approval.applyPlan(f.user, f.project, id(plan), true, hash(plan)),
                () -> approval.applyPlan(f.user, f.project, id(plan), true, hash(plan))))
                .allMatch(value -> value instanceof Map);
        assertThat(learning.list(f.project)).hasSize(3);
        var current = artifacts.get(f.user, f.project, id(plan));
        assertThat((List<?>) current.get("audit")).hasSize(1);
        assertThat(((Number) current.get("version")).longValue()).isGreaterThan(0);
        assertThat(status(f)).isEqualTo("SUCCEEDED");
    }

    @Test
    void lastConfirmationRacingFinishCannotLeaveRunAwaiting() throws Exception {
        var f = fixture();
        var plan = plan(f);
        finish(f);
        var stale = Map.<String, Object>of("answer", "saved response", "artifacts", List.of(plan));
        assertThat(parallel(
                () -> approval.applyPlan(f.user, f.project, id(plan), true, hash(plan)),
                () -> { runs.finish(f.run, "AWAITING_CONFIRMATION", "stale snapshot", stale); return true; }))
                .noneMatch(value -> value instanceof Throwable);
        assertThat(status(f)).isEqualTo("SUCCEEDED");
        assertThat(learning.list(f.project)).hasSize(3);
    }

    @Test
    void confirmationAndFinishNeverPromoteFailedOrInterruptedRuns() {
        for (String failure : List.of("FAILED", "INTERRUPTED", "LIMIT", "LIMIT_REACHED", "CANCELLED")) {
            var f = fixture();
            var plan = plan(f);
            runs.finish(f.run, failure, "preserved failure", Map.of());
            approval.applyPlan(f.user, f.project, id(plan), true, hash(plan));
            runs.finish(f.run, "SUCCEEDED", "late success", Map.of());
            assertThat(status(f)).isEqualTo(failure);
        }
    }

    @Test
    void lateFailureCannotOverwriteCompletedOrAwaitingRuns() {
        var completed = fixture();
        runs.finish(completed.run, "SUCCEEDED", "completed", Map.of("answer", "saved"));
        runs.finish(completed.run, "FAILED", "late callback", Map.of());
        assertThat(status(completed)).isEqualTo("SUCCEEDED");
        var awaiting = fixture(); plan(awaiting); finish(awaiting);
        runs.finish(awaiting.run, "CANCELLED", "late callback", Map.of());
        assertThat(status(awaiting)).isEqualTo("AWAITING_CONFIRMATION");
    }

    @Test
    void requestReplayBindsBodyAndReturnsStoredAnswerWithCurrentArtifacts() {
        var f = fixture();
        String key = UUID.randomUUID().toString();
        String requestHash = Hashing.sha256("same request");
        String run = runs.start(f.user, f.project, f.session, "same request", key, requestHash);
        var plan = artifacts.save(run, f.user, f.project, "study_planner", "LEARNING_PLAN",
                planContent("已存"), List.of(), List.of());
        runs.saveSpecification(run, Map.of("goal", "same request", "outputs", List.of("LEARNING_PLAN")));
        runs.saveSpecification(run, Map.of("goal", "same request", "outputs", List.of("LEARNING_PLAN")));
        assertConflict(() -> runs.saveSpecification(run, Map.of("goal", "changed")));
        assertConflict(() -> runs.replay(f.user, f.project, key, requestHash));
        assertConflict(() -> runs.start(f.user, f.project, f.session, "same request", key, requestHash));
        runs.finish(run, "AWAITING_CONFIRMATION", "saved", Map.of("answer", "original answer",
                "citations", List.of(Map.of("id", "E1")), "stats", Map.of("delegations", 1)));
        var initial = runs.replay(f.user, f.project, key, requestHash).orElseThrow();
        assertThat(initial).containsEntry("answer", "original answer").containsEntry("session_id", f.session)
                .containsEntry("run_id", run).containsEntry("status", "AWAITING_CONFIRMATION");
        assertConflict(() -> runs.replay(f.user, f.project, key, Hashing.sha256("changed request")));
        approval.applyPlan(f.user, f.project, id(plan), true, hash(plan));
        var current = runs.replay(f.user, f.project, key, requestHash).orElseThrow();
        assertThat(current).containsEntry("answer", "original answer").containsEntry("status", "SUCCEEDED");
        assertThat(((Map<?, ?>) ((List<?>) current.get("artifacts")).get(0)).get("status")).isEqualTo("APPLIED");
        assertThat(runs.get(f.user, f.project, run).get("task_specification")).isNotNull();
        assertThat(runs.replay(f.user, f.project, UUID.randomUUID().toString(), requestHash)).isEmpty();
    }

    @Test
    void unfinishedFailedInterruptedAndLimitRequestKeysCannotAutomaticallyRetry() {
        var f = fixture();
        for (String state : List.of("RUNNING", "FAILED", "INTERRUPTED", "LIMIT", "LIMIT_REACHED")) {
            String key = UUID.randomUUID().toString();
            String requestHash = Hashing.sha256(state);
            String run = runs.start(f.user, f.project, f.session, state, key, requestHash);
            if (!"RUNNING".equals(state)) runs.finish(run, state, state, Map.of());
            assertConflict(() -> runs.replay(f.user, f.project, key, requestHash));
            assertConflict(() -> runs.start(f.user, f.project, f.session, state, key, requestHash));
        }
    }

    @Test
    void databaseUniqueConstraintAcceptsOnlyOneConcurrentRequestKey() throws Exception {
        var f = fixture();
        String key = UUID.randomUUID().toString();
        String requestHash = Hashing.sha256("racing request");
        var results = parallel(
                () -> runs.start(f.user, f.project, f.session, "racing request", key, requestHash),
                () -> runs.start(f.user, f.project, f.session, "racing request", key, requestHash));
        assertThat(results.stream().filter(value -> value instanceof String).count()).isEqualTo(1);
        assertThat(results.stream().filter(value -> value instanceof ApiException).count()).isEqualTo(1);
        assertThat(runRecords.findTop30ByUserIdAndProjectIdOrderByCreatedAtDesc(f.user, f.project)
                .stream().filter(run -> key.equals(run.getRequestId())).count()).isEqualTo(1);
    }

    @Test
    void scriptReviewMustBindTargetAndHashAndExplicitOverrideIsAudited() {
        var f = fixture();
        var script = script(f);
        var wrongHash = review(f, script, id(script), Hashing.sha256("old content"), true);
        var wrongTarget = review(f, script, UUID.randomUUID().toString(), hash(script), true);
        var rejected = review(f, script, id(script), hash(script), false);
        finish(f);
        assertConflict(() -> approval.synthesize(f.user, f.project, id(script), true, null));
        for (var report : List.of(wrongHash, wrongTarget, rejected))
            assertConflict(() -> approval.synthesize(f.user, f.project, id(script), true, null,
                    hash(script), id(report), false, null));
        assertThatThrownBy(() -> approval.synthesize(f.user, f.project, id(script), true, null,
                hash(script), null, true, "short")).isInstanceOf(ApiException.class);
        when(speech.synthesize(anyString(), anyString(), any())).thenAnswer(call -> "/media/" + call.getArgument(0) + ".wav");
        approval.synthesize(f.user, f.project, id(script), true, null,
                hash(script), null, true, "用户已人工逐句核查讲稿内容并接受风险");
        approval.synthesize(f.user, f.project, id(script), true, null);
        verify(speech, times(1)).synthesize(anyString(), anyString(), any());
        var current = artifacts.get(f.user, f.project, id(script));
        var audit = (Map<?, ?>) ((List<?>) current.get("audit")).get(0);
        assertThat(audit.get("override_review")).isEqualTo(true);
        assertThat(audit.get("override_reason")).isEqualTo("用户已人工逐句核查讲稿内容并接受风险");
        assertThat(audit.get("content_hash")).isEqualTo(hash(script));
        assertThat(status(f)).isEqualTo("SUCCEEDED");
    }

    @Test
    void matchingReviewPermitsSynthesisAndDuplicateDuringGenerationDoesNotRerunTts() throws Exception {
        var f = fixture();
        var script = script(f);
        var review = review(f, script, id(script), hash(script), true);
        finish(f);
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        when(speech.synthesize(anyString(), anyString(), any())).thenAnswer(call -> {
            generating.countDown();
            if (!complete.await(10, TimeUnit.SECONDS)) throw new AssertionError("test synthesis timed out");
            return "/media/" + call.getArgument(0) + ".wav";
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> first = pool.submit(() -> approval.synthesize(f.user, f.project, id(script), true,
                    null, hash(script), id(review), false, null));
            assertThat(generating.await(10, TimeUnit.SECONDS)).isTrue();
            assertConflict(() -> approval.synthesize(f.user, f.project, id(script), true, null,
                    hash(script), id(review), false, null));
            complete.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).containsEntry("status", "READY");
            verify(speech, times(1)).synthesize(anyString(), anyString(), any());
        } finally {
            complete.countDown(); pool.shutdownNow();
        }
    }

    @Test
    void audioProducedBeforeDatabaseFailureIsRetainedAndRequiresExplicitRecovery(@TempDir Path temp) throws Exception {
        var f = fixture();
        var script = script(f);
        var review = review(f, script, id(script), hash(script), true);
        finish(f);
        when(speech.synthesize(anyString(), anyString(), any())).thenAnswer(call -> "/media/" + call.getArgument(0) + ".wav");
        doThrow(new IllegalStateException("simulated commit window"))
                .when(repository).addProjectRecord(eq(f.project), eq("lesson"), anyString(), anyString(), anyMap());
        assertThatThrownBy(() -> approval.synthesize(f.user, f.project, id(script), true, null,
                hash(script), id(review), false, null)).hasMessageContaining("simulated commit window");
        var retained = artifacts.get(f.user, f.project, id(script));
        assertThat(retained).containsEntry("status", "NEEDS_REVIEW");
        String audioId = String.valueOf(retained.get("audio_id"));
        assertConflict(() -> approval.synthesize(f.user, f.project, id(script), true, null,
                hash(script), id(review), false, null));
        verify(speech, times(1)).synthesize(anyString(), anyString(), any());
        verify(speech, never()).remove(anyString());
        doCallRealMethod().when(repository).addProjectRecord(eq(f.project), eq("lesson"), anyString(), anyString(), anyMap());
        Path wav = temp.resolve(audioId + ".wav");
        Files.write(wav, wavBytes());
        when(speech.audioFile(audioId + ".wav")).thenReturn(wav);
        assertConflict(() -> approval.recoverSynthesis(f.user, f.project, id(script), true,
                hash(script), UUID.randomUUID().toString(), "/media/" + audioId + ".wav"));
        assertThat(approval.recoverSynthesis(f.user, f.project, id(script), true,
                hash(script), audioId, "/media/" + audioId + ".wav")).containsEntry("status", "READY");
        approval.recoverSynthesis(f.user, f.project, id(script), true,
                hash(script), audioId, "/media/" + audioId + ".wav");
        assertThat(repository.projectRecords(f.user, f.project).stream()
                .filter(record -> "lesson".equals(record.get("record_type")))).hasSize(1);
        assertThat(status(f)).isEqualTo("SUCCEEDED");
        verify(speech, times(1)).synthesize(anyString(), anyString(), any());
        verify(speech, never()).remove(anyString());
    }

    @Test
    void restartMarksSynthesisForReviewPreservesAudioIdAndDoesNotPromoteInterruptedRun() {
        var f = fixture();
        var script = script(f);
        var review = review(f, script, id(script), hash(script), true);
        // 模拟已中断的模型任务，其有效草稿由用户另行明确确认；不能伪造迟到回调改写已完成状态。
        runs.finish(f.run, "INTERRUPTED", "process stopped", Map.of());
        var generating = artifacts.beginSynthesis(f.user, f.project, id(script), hash(script), id(review), false, null);
        artifacts.markInterruptedSynthesis();
        var current = artifacts.get(f.user, f.project, id(script));
        assertThat(current).containsEntry("status", "NEEDS_REVIEW").containsEntry("audio_id", generating.get("audio_id"));
        assertConflict(() -> artifacts.beginSynthesis(f.user, f.project, id(script), hash(script), id(review), false, null));
        assertThat(status(f)).isEqualTo("INTERRUPTED");
        verify(speech, never()).synthesize(anyString(), anyString(), any());
    }

    @Test
    void recoveryValidatesHashStateAndAttemptBeforeReadingFileAndRejectsMissingOrIncompleteWav(@TempDir Path temp) throws Exception {
        var f = fixture();
        var script = script(f);
        var review = review(f, script, id(script), hash(script), true);
        finish(f);
        var generating = artifacts.beginSynthesis(f.user, f.project, id(script), hash(script), id(review), false, null);
        String oldAudioId = String.valueOf(generating.get("audio_id"));
        String oldUrl = "/media/" + oldAudioId + ".wav";
        assertConflict(() -> approval.recoverSynthesis(f.user, f.project, id(script), true,
                Hashing.sha256("changed"), oldAudioId, oldUrl));
        assertConflict(() -> approval.recoverSynthesis(f.user, f.project, id(script), true,
                hash(script), oldAudioId, oldUrl));
        artifacts.failSynthesis(f.user, f.project, id(script));
        assertConflict(() -> approval.recoverSynthesis(f.user, f.project, id(script), true,
                hash(script), UUID.randomUUID().toString(), oldUrl));
        verify(speech, never()).audioFile(anyString());
        when(speech.audioFile(oldAudioId + ".wav")).thenThrow(new ApiException(HttpStatus.NOT_FOUND, "not generated"));
        assertConflict(() -> approval.recoverSynthesis(f.user, f.project, id(script), true,
                hash(script), oldAudioId, oldUrl));
        Path invalid = temp.resolve(oldAudioId + ".wav");
        byte[] truncated = Arrays.copyOf(wavBytes(), 48);
        Files.write(invalid, truncated);
        doReturn(invalid).when(speech).audioFile(oldAudioId + ".wav");
        assertConflict(() -> approval.recoverSynthesis(f.user, f.project, id(script), true,
                hash(script), oldAudioId, oldUrl));
        assertThat(artifacts.get(f.user, f.project, id(script))).containsEntry("status", "NEEDS_REVIEW");
        verify(speech, never()).synthesize(anyString(), anyString(), any());
        verify(speech, never()).remove(anyString());
    }

    @Test
    void explicitRetryResetsOnlyBoundInterruptedAttemptAndPreservesAuditWithoutRunningTts() {
        var f = fixture();
        var script = script(f);
        var review = review(f, script, id(script), hash(script), true);
        finish(f);
        var generating = artifacts.beginSynthesis(f.user, f.project, id(script), hash(script), id(review), false, null);
        String oldAudioId = String.valueOf(generating.get("audio_id"));
        assertConflict(() -> approval.retrySynthesis(f.user, f.project, id(script), true,
                hash(script), oldAudioId, "已人工检查失败音频并决定稍后再次确认合成"));
        artifacts.failSynthesis(f.user, f.project, id(script));
        assertThatThrownBy(() -> approval.retrySynthesis(f.user, f.project, id(script), false,
                hash(script), oldAudioId, "已人工检查失败音频并决定稍后再次确认合成"))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertConflict(() -> approval.retrySynthesis(f.user, f.project, id(script), true,
                Hashing.sha256("changed"), oldAudioId, "已人工检查失败音频并决定稍后再次确认合成"));
        assertConflict(() -> approval.retrySynthesis(f.user, f.project, id(script), true,
                hash(script), UUID.randomUUID().toString(), "已人工检查失败音频并决定稍后再次确认合成"));
        for (String reason : List.of("short", "x".repeat(501)))
            assertThatThrownBy(() -> approval.retrySynthesis(f.user, f.project, id(script), true,
                    hash(script), oldAudioId, reason)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        String reason = "已人工检查失败音频并决定稍后再次确认合成";
        var reset = approval.retrySynthesis(f.user, f.project, id(script), true, hash(script), oldAudioId, reason);
        assertThat(reset).containsEntry("status", "DRAFT").containsEntry("audio_id", null).containsEntry("audio_url", null);
        var audit = (Map<?, ?>) ((List<?>) reset.get("audit")).get(2);
        assertThat(audit.get("action")).isEqualTo("RESET_SYNTHESIS_ATTEMPT");
        assertThat(audit.get("confirmed")).isEqualTo(true);
        assertThat(audit.get("old_audio_id")).isEqualTo(oldAudioId);
        assertThat(audit.get("old_audio_url")).isEqualTo("/media/" + oldAudioId + ".wav");
        assertThat(audit.get("old_recorded_audio_url")).isNull();
        assertThat(audit.get("old_expected_audio_url")).isEqualTo("/media/" + oldAudioId + ".wav");
        assertThat(audit.get("reason")).isEqualTo(reason);
        verify(speech, never()).synthesize(anyString(), anyString(), any());
        verify(speech, never()).audioFile(anyString());
        verify(speech, never()).remove(anyString());
        assertConflict(() -> approval.synthesize(f.user, f.project, id(script), true, null));
        assertConflict(() -> approval.synthesize(f.user, f.project, id(script), true, null,
                hash(script), null, false, null));
        when(speech.synthesize(anyString(), anyString(), any())).thenAnswer(call -> "/media/" + call.getArgument(0) + ".wav");
        var ready = approval.synthesize(f.user, f.project, id(script), true, null, hash(script), id(review), false, null);
        assertThat(ready).containsEntry("status", "READY");
        assertThat(ready.get("audio_id")).isNotEqualTo(oldAudioId);
        verify(speech, times(1)).synthesize(anyString(), anyString(), any());
        verify(speech, never()).remove(anyString());
    }

    @Test
    void lateFailureFromOldAttemptCannotInterruptNewlyConfirmedAttempt() {
        var f = fixture();
        var script = script(f);
        var review = review(f, script, id(script), hash(script), true);
        finish(f);
        var first = artifacts.beginSynthesis(f.user, f.project, id(script), hash(script), id(review), false, null);
        String oldId = String.valueOf(first.get("audio_id"));
        artifacts.failSynthesis(f.user, f.project, id(script), oldId);
        approval.retrySynthesis(f.user, f.project, id(script), true, hash(script), oldId,
                "已人工检查中断结果并决定再次单独确认合成");
        var next = artifacts.beginSynthesis(f.user, f.project, id(script), hash(script), id(review), false, null);
        assertThat(next.get("audio_id")).isNotEqualTo(oldId);
        artifacts.failSynthesis(f.user, f.project, id(script), oldId);
        assertThat(artifacts.get(f.user, f.project, id(script)))
                .containsEntry("status", "GENERATING").containsEntry("audio_id", next.get("audio_id"));
        artifacts.failSynthesis(f.user, f.project, id(script), String.valueOf(next.get("audio_id")));
        assertThat(artifacts.get(f.user, f.project, id(script))).containsEntry("status", "NEEDS_REVIEW");
    }

    private Fixture fixture() {
        long user = ((Number) repository.createUser("state_" + UUID.randomUUID().toString().substring(0, 10), "test").get("id")).longValue();
        long project = ((Number) repository.ensureProject(user, "并发一致性").get("id")).longValue();
        String session = repository.openSession(user, project, null);
        return new Fixture(user, project, session, runs.start(user, project, session, "test"));
    }
    private Map<String, Object> plan(Fixture f) { return plan(f, "知识"); }
    private Map<String, Object> plan(Fixture f, String prefix) {
        return artifacts.save(f.run, f.user, f.project, "study_planner", "LEARNING_PLAN", planContent(prefix), List.of(), List.of());
    }
    private String planContent(String prefix) {
        return json.writeValueAsString(Map.of("points", List.of(Map.of("title", prefix + "一"),
                Map.of("title", prefix + "二"), Map.of("title", prefix + "三"))));
    }
    private Map<String, Object> script(Fixture f) {
        return artifacts.save(f.run, f.user, f.project, "script_writer", "SCRIPT_DRAFT",
                "这是一份已经由用户逐句检查的课程讲稿。我们从随机事件的定义开始，解释概率的含义，并用实例核查每一步推论。", List.of(), List.of());
    }
    private Map<String, Object> review(Fixture f, Map<String, Object> script, String target, String hash, boolean approved) {
        return artifacts.save(f.run, f.user, f.project, "review_agent", "REVIEW_REPORT",
                json.writeValueAsString(Map.of("reviewed_artifact_id", target, "content_hash", hash,
                        "checks", Map.of("topic", true, "genre", true, "language", true, "ending", true,
                                "length", true, "citations", true, "readable", true),
                        "approved", approved, "issues", List.of(), "reason", "人工测试审核报告")), List.of(id(script)), List.of());
    }
    private void finish(Fixture f) {
        runs.finish(f.run, "AWAITING_CONFIRMATION", "awaiting", Map.of("answer", "saved response", "stats", Map.of()));
    }
    private String status(Fixture f) { return String.valueOf(runs.get(f.user, f.project, f.run).get("status")); }
    private String id(Map<String, Object> value) { return String.valueOf(value.get("id")); }
    private String hash(Map<String, Object> value) { return String.valueOf(value.get("content_hash")); }
    private void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
    }
    private List<Object> parallel(Supplier<?> first, Supplier<?> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> attempt(first, start));
            var b = pool.submit(() -> attempt(second, start));
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    private Object attempt(Supplier<?> action, CountDownLatch start) throws InterruptedException {
        start.await();
        try { return action.get(); } catch (RuntimeException failure) { return failure; }
    }
    private byte[] wavBytes() {
        ByteBuffer out = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(44).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        out.putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(8).putLong(0);
        return out.array();
    }
    private record Fixture(long user, long project, String session, String run) { }
}
