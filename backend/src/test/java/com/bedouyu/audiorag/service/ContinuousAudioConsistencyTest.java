package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.agent.audio.AudioAgent.CoursePlan;
import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.repository.*;
import com.bedouyu.audiorag.rag.*;
import com.bedouyu.audiorag.service.ContinuousAudioService.QueueCommand;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.tools.research.WebSearchService;
import com.bedouyu.audiorag.tools.storage.MinioStorageService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:continuous_consistency;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop", "audiorag.llm.api-key="
})
class ContinuousAudioConsistencyTest {
    @Autowired ContinuousAudioStore store;
    @Autowired ContinuousAudioQueueRepository queues;
    @Autowired ContinuousAudioSegmentRepository segments;
    @MockitoSpyBean AudioRagRepository records;
    @MockitoSpyBean LearningPlanService learning;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean AudioAgent agent;
    @MockitoBean QdrantService qdrant;
    @MockitoBean OnnxEmbeddingService embeddings;
    @MockitoBean MinioStorageService minio;
    @MockitoBean SpeechService speech;
    @MockitoBean WebSearchService web;

    @Test void duplicateStartKeepsOriginalQueueAndDoesNotReserveAgain() {
        var f = fixture();
        var first = store.reserve(f.user, command(f, f.request));
        var second = store.reserve(f.user, command(f, f.request.toUpperCase(Locale.ROOT)));
        assertThat(first.created()).isTrue(); assertThat(second.created()).isFalse();
        assertThat(second.queueId()).isEqualTo(first.queueId());
        assertThat(queues.findByUserIdAndProjectId(f.user, f.project)).hasSize(1);
        assertThat(store.status(f.user, first.queueId())).containsEntry("status", "PREPARING");
    }

    @Test void requestCannotBeReusedForChangedPayload() {
        var f = fixture(); store.reserve(f.user, command(f, f.request));
        conflict(() -> store.reserve(f.user, new QueueCommand("changed", f.project, 10, 1, "beginner", "", 2, f.request)));
    }

    @Test void singleAndContinuousModesCannotShareARequestKey() {
        var f = fixture(); store.reserve(f.user, command(f, f.request));
        conflict(() -> store.reserve(f.user, new QueueCommand("topic", f.project, 10, 1, "beginner", "", 2, f.request, "SINGLE")));
    }

    @Test void singleSegmentCommitsNonContinuousLessonMetadata() {
        var f = fixture();
        var creation = store.reserve(f.user, new QueueCommand("topic", f.project, 3, 3, "beginner", "", 1, f.request, "SINGLE"));
        var point = learning.reserveNext(f.project, "Topic");
        store.prepared(f.user, creation, new CoursePlan("Single Title", "script", List.of(), point.id()), List.of("A single lesson without continuous segmentation."));
        var prepared = new Fixture(f.user, f.project, f.request, creation.queueId());
        var claim = claimed(prepared); store.complete(f.user, claim, url(claim)); store.releaseGeneration(f.user, prepared.queue, claim.token());
        var record = records.projectRecords(f.user, f.project).stream().filter(r -> "lesson".equals(r.get("record_type"))).findFirst().orElseThrow();
        assertThat(record).containsEntry("title", "Single Title");
        assertThat((Map<String, Object>) record.get("metadata")).containsEntry("continuous", false);
        assertThat(store.status(f.user, prepared.queue)).containsEntry("mode", "SINGLE").containsEntry("status", "COMPLETE");
    }

    @Test void firstFragmentDoesNotMarkWholeCourseAsCovered() {
        var f = fixture(); var creation = store.reserve(f.user, command(f, f.request));
        var point = learning.reserveNext(f.project, "Topic");
        store.prepared(f.user, creation, new CoursePlan("Course", "first\n\nsecond", List.of(), point.id()), List.of("first", "second"));
        var prepared = new Fixture(f.user, f.project, f.request, creation.queueId());
        var first = claimed(prepared); store.complete(f.user, first, url(first));
        assertThat(learning.list(f.project).stream().filter(p -> ((Number) p.get("id")).longValue() == point.id()).findFirst().orElseThrow())
                .containsEntry("status", "QUEUED");
        var second = store.claimNext(f.user, prepared.queue, first.token()).orElseThrow(); store.complete(f.user, second, url(second));
        store.releaseGeneration(f.user, prepared.queue, first.token());
        assertThat(learning.list(f.project).stream().filter(p -> ((Number) p.get("id")).longValue() == point.id()).findFirst().orElseThrow())
                .containsEntry("status", "COMPLETED");
    }

    @Test void parallelRequestsAreSerializedByDatabaseProjectLock() throws Exception {
        var f = fixture();
        var results = parallel(() -> store.reserve(f.user, command(f, f.request)), () -> store.reserve(f.user, command(f, f.request)));
        assertThat(results).allMatch(v -> v instanceof ContinuousAudioStore.Creation);
        var creations = results.stream().map(v -> (ContinuousAudioStore.Creation) v).toList();
        assertThat(creations.stream().filter(ContinuousAudioStore.Creation::created).count()).isEqualTo(1);
        assertThat(creations).extracting(ContinuousAudioStore.Creation::queueId).containsOnly(creations.get(0).queueId());
    }

    @Test void failedPreparationIsReturnedButNeverAutomaticallyReplayed() {
        var f = fixture(); var creation = store.reserve(f.user, command(f, f.request));
        store.preparationFailed(f.user, creation, "mock model failed");
        assertThat(store.reserve(f.user, command(f, f.request)).created()).isFalse();
        assertThat(store.status(f.user, creation.queueId())).containsEntry("status", "FAILED");
        conflict(() -> store.claimGeneration(f.user, creation.queueId()));
        verifyNoInteractions(agent, speech);
    }

    @Test void expiredPreparationDoesNotReplayModelOrStealAnActiveLease() {
        var f = fixture(); var creation = store.reserve(f.user, command(f, f.request));
        assertThat(store.status(f.user, creation.queueId())).containsEntry("status", "PREPARING");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var queue = queues.findLockedById(creation.queueId()).orElseThrow();
            // Expire only the lease; do not change PREPARING into another state.
            queue.renew(Instant.now().minusSeconds(1));
        });
        assertThat(store.status(f.user, creation.queueId())).containsEntry("status", "NEEDS_REVIEW");
        conflict(() -> store.claimGeneration(f.user, creation.queueId()));
        verifyNoInteractions(agent, speech);
    }

    @Test void oneSegmentClaimAcrossInstancesAndNoAutomaticReclaim() throws Exception {
        var f = prepared();
        var tokens = parallel(() -> store.claimGeneration(f.user, f.queue), () -> store.claimGeneration(f.user, f.queue));
        assertThat(tokens).allMatch(v -> v instanceof Optional);
        assertThat(tokens.stream().map(v -> (Optional<?>) v).filter(Optional::isPresent).count()).isEqualTo(1);
        String token = tokens.stream().map(v -> (Optional<String>) v).flatMap(Optional::stream).findFirst().orElseThrow();
        var claim = store.claimNext(f.user, f.queue, token).orElseThrow();
        assertThat(store.claimGeneration(f.user, f.queue)).isEmpty();
        assertThat(store.status(f.user, f.queue)).containsEntry("status", "GENERATING");
        assertThat(segment(f)).containsEntry("status", "SYNTHESIZING").containsEntry("audio_id", claim.audioId());
    }

    @Test void recordAndReadyAreIdempotentWithinOneTransaction() throws Exception {
        var f = prepared(); var claim = claimed(f);
        var outcomes = parallel(() -> { store.complete(f.user, claim, url(claim)); return true; },
                () -> { store.complete(f.user, claim, url(claim)); return true; });
        assertThat(outcomes).noneMatch(v -> v instanceof Throwable);
        store.releaseGeneration(f.user, f.queue, claim.token());
        assertThat(records.projectRecords(f.user, f.project)).filteredOn(r -> "lesson".equals(r.get("record_type"))).hasSize(1);
        assertThat(store.status(f.user, f.queue)).containsEntry("status", "COMPLETE");
        assertThat(segment(f)).containsEntry("status", "READY");
    }

    @Test void databaseFailureRollsBackRecordAndReadyTogether() {
        var f = prepared(); var claim = claimed(f);
        doThrow(new IllegalStateException("mock DB failure")).when(records).addProjectRecord(eq(f.project), eq("lesson"), anyString(), anyString(), anyMap());
        try {
            // Repository exception translation is part of the real Spring boundary.
            assertThatThrownBy(() -> store.complete(f.user, claim, url(claim)))
                    .isInstanceOf(InvalidDataAccessApiUsageException.class)
                    .hasMessageContaining("mock DB failure")
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("mock DB failure");
            assertThat(records.projectRecords(f.user, f.project)).filteredOn(r -> "lesson".equals(r.get("record_type"))).isEmpty();
            assertThat(segment(f)).containsEntry("status", "SYNTHESIZING");
        } finally { reset(records); }
    }

    @Test void failureAfterRecordInsertAndReadyStillRollsBackWholeTransaction() {
        var f = prepared(); var claim = claimed(f);
        doThrow(new IllegalStateException("post-insert progress failure")).when(learning).complete(claim.draft().knowledgePointId());
        try {
            assertThatThrownBy(() -> store.complete(f.user, claim, url(claim))).hasMessage("post-insert progress failure");
            assertThat(records.projectRecords(f.user, f.project)).filteredOn(r -> "lesson".equals(r.get("record_type"))).isEmpty();
            assertThat(segment(f)).containsEntry("status", "SYNTHESIZING");
            assertThat(learning.list(f.project).stream().filter(p -> ((Number) p.get("id")).longValue() == claim.draft().knowledgePointId()).findFirst().orElseThrow())
                    .containsEntry("status", "QUEUED");
        } finally { reset(learning); }
    }

    @Test void rejectedWorkerReleasesUnstartedClaimAndAllowsManualRefill() {
        var f = prepared(); String rejectedToken = store.claimGeneration(f.user, f.queue).orElseThrow();
        store.releaseGeneration(f.user, f.queue, rejectedToken);
        assertThat(store.status(f.user, f.queue)).containsEntry("status", "READY");
        assertThat(segment(f)).containsEntry("status", "PENDING");
        var nextToken = store.claimGeneration(f.user, f.queue).orElseThrow();
        assertThat(nextToken).isNotEqualTo(rejectedToken);
        assertThat(store.claimNext(f.user, f.queue, nextToken)).isPresent();
    }

    @Test void uncertainSynthesisRetainsAudioIdAndRefillRejectsIt() {
        var f = prepared(); var claim = claimed(f);
        store.uncertain(f.user, claim, "provider may have produced a WAV");
        store.releaseGeneration(f.user, f.queue, claim.token());
        assertThat(segment(f)).containsEntry("status", "NEEDS_REVIEW").containsEntry("audio_id", claim.audioId());
        assertThat(store.status(f.user, f.queue)).containsEntry("status", "NEEDS_REVIEW");
        conflict(() -> store.claimGeneration(f.user, f.queue));
        verifyNoInteractions(speech);
    }

    @Test void leaseExpirationTurnsActiveAttemptIntoNeedsReviewWithoutChangingAudioId() {
        var f = prepared(); var claim = claimed(f);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            queues.findLockedById(f.queue).orElseThrow().renew(Instant.now().minusSeconds(1));
        });
        assertThat(store.status(f.user, f.queue)).containsEntry("status", "NEEDS_REVIEW");
        assertThat(segment(f)).containsEntry("audio_id", claim.audioId()).containsEntry("status", "NEEDS_REVIEW");
        conflict(() -> store.complete(f.user, claim, url(claim)));
        assertThat(records.projectRecords(f.user, f.project)).filteredOn(r -> "lesson".equals(r.get("record_type"))).isEmpty();
    }

    @Test void explicitRecoveryBindsHashAndAudioIdAndDoesNotDuplicateLesson() throws Exception {
        var f = prepared(); var claim = claimed(f); store.uncertain(f.user, claim, "unknown");
        assertThatThrownBy(() -> store.recoveryCandidate(f.user, f.queue, 1, false, claim.scriptHash(), claim.audioId())).isInstanceOf(ApiException.class);
        conflict(() -> store.recoveryCandidate(f.user, f.queue, 1, true, Hashing.sha256("wrong"), claim.audioId()));
        conflict(() -> store.recoveryCandidate(f.user, f.queue, 1, true, claim.scriptHash(), UUID.randomUUID().toString()));
        store.recoveryCandidate(f.user, f.queue, 1, true, claim.scriptHash(), claim.audioId());
        var results = parallel(() -> store.recovered(f.user, f.queue, 1, claim.scriptHash(), claim.audioId()),
                () -> store.recovered(f.user, f.queue, 1, claim.scriptHash(), claim.audioId()));
        assertThat(results).noneMatch(v -> v instanceof Throwable);
        assertThat(segment(f)).containsEntry("status", "READY");
        assertThat((List<?>) segment(f).get("audit")).hasSize(1);
        assertThat(records.projectRecords(f.user, f.project)).filteredOn(r -> "lesson".equals(r.get("record_type"))).hasSize(1);
    }

    @Test void resetAuditsOldAttemptAndNeverPerformsSynthesis() {
        var f = prepared(); var claim = claimed(f); store.uncertain(f.user, claim, "unknown");
        assertThatThrownBy(() -> store.reset(f.user, f.queue, 1, true, claim.scriptHash(), claim.audioId(), "short")).isInstanceOf(ApiException.class);
        store.reset(f.user, f.queue, 1, true, claim.scriptHash(), claim.audioId(), "人工检查原音频未完成，明确建立新的合成尝试");
        var current = segment(f);
        assertThat(current).containsEntry("status", "PENDING"); assertThat(current.get("audio_id")).isNotEqualTo(claim.audioId());
        assertThat((List<?>) current.get("audit")).hasSize(1); verifyNoInteractions(speech);
        store.uncertain(f.user, claim, "late callback");
        assertThat(segment(f)).containsEntry("status", "PENDING");
        conflict(() -> store.complete(f.user, claim, url(claim)));
    }

    @Test void cancelledQueueRejectsCallbacksRecoveryAndReset() {
        var f = prepared(); var claim = claimed(f); store.cancelProject(f.user, f.project);
        store.uncertain(f.user, claim, "late");
        conflict(() -> store.complete(f.user, claim, url(claim)));
        conflict(() -> store.recoveryCandidate(f.user, f.queue, 1, true, claim.scriptHash(), claim.audioId()));
        conflict(() -> store.reset(f.user, f.queue, 1, true, claim.scriptHash(), claim.audioId(), "人工检查后尝试重置，但队列已取消"));
        assertThat(store.status(f.user, f.queue)).containsEntry("status", "CANCELLED"); verifyNoInteractions(speech);
    }

    @Test void anotherUserCannotReadOrMutateQueue() {
        var f = prepared(); var claim = claimed(f);
        assertThatThrownBy(() -> store.status(f.user + 99999, f.queue)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> store.reset(f.user + 99999, f.queue, 1, true, claim.scriptHash(), claim.audioId(), "另一个用户不应该能够修改这个课程队列")).isInstanceOf(ApiException.class);
    }

    @Test void versionsIncreaseAndUncertainStateRemainsAfterFreshDatabaseRead() {
        var f = prepared(); long before = ((Number) store.status(f.user, f.queue).get("version")).longValue();
        var claim = claimed(f); store.uncertain(f.user, claim, "unknown");
        var fresh = store.status(f.user, f.queue);
        assertThat(((Number) fresh.get("version")).longValue()).isGreaterThan(before);
        assertThat(fresh).containsEntry("storage", "mysql-durable").containsEntry("status", "NEEDS_REVIEW");
    }

    private Fixture fixture() {
        // Keep the fixture within the real users.username VARCHAR(40) contract.
        long user = ((Number) records.createUser("cq-" + UUID.randomUUID(), "test-only").get("id")).longValue();
        long project = ((Number) records.ensureProject(user, "continuous-topic").get("id")).longValue();
        return new Fixture(user, project, UUID.randomUUID().toString(), null);
    }
    private Fixture prepared() {
        var f = fixture(); var creation = store.reserve(f.user, command(f, f.request));
        var point = learning.reserveNext(f.project, "Topic");
        store.prepared(f.user, creation, new CoursePlan("Course", "script", List.of(), point.id()), List.of("A test-only course script for one segment."));
        return new Fixture(f.user, f.project, f.request, creation.queueId());
    }
    private QueueCommand command(Fixture f, String request) { return new QueueCommand("topic", f.project, 10, 1, "beginner", "", 2, request); }
    private ContinuousAudioStore.Claim claimed(Fixture f) {
        String token = store.claimGeneration(f.user, f.queue).orElseThrow(); return store.claimNext(f.user, f.queue, token).orElseThrow();
    }
    private Map<String, Object> segment(Fixture f) { return ((List<Map<String, Object>>) store.status(f.user, f.queue).get("segments")).get(0); }
    private String url(ContinuousAudioStore.Claim c) { return "/media/" + c.audioId() + ".wav"; }
    private void conflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
    }
    private List<Object> parallel(Supplier<Object> first, Supplier<Object> second) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> outcome(first, start)); var b = pool.submit(() -> outcome(second, start)); start.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    private Object outcome(Supplier<Object> supplier, CountDownLatch start) { try { start.await(); return supplier.get(); } catch (Throwable e) { return e; } }
    private record Fixture(long user, long project, String request, String queue) { }
}
