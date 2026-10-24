package com.bedouyu.audiorag.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.model.entity.*;
import com.bedouyu.audiorag.repository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/** User review is a durable boundary: staging/rejection cannot mutate playback or retire a WAV. */
class ContinuousAudioScriptRevisionTest {
    private static final String ORIGINAL = "这是第一版中文讲稿，我们一起讨论月亮的来历。";
    private static final String EDITED = "这是经过修改的中文讲稿，我们一起讨论月亮的形成与来历。";
    private final ContinuousAudioQueueRepository queues = mock(ContinuousAudioQueueRepository.class);
    private final ContinuousAudioSegmentRepository segments = mock(ContinuousAudioSegmentRepository.class);
    private final ContinuousAudioScriptRevisionRepository proposals = mock(ContinuousAudioScriptRevisionRepository.class);
    private final LearningProjectRepository projects = mock(LearningProjectRepository.class);
    private final AudioRagRepository records = mock(AudioRagRepository.class);
    private final LearningPlanService learning = mock(LearningPlanService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ContinuousAudioStore store = new ContinuousAudioStore(queues, segments, projects, records, learning, json);
    private final String id = UUID.randomUUID().toString();
    private final List<ContinuousAudioSegment> live = new ArrayList<>();
    private final Map<String, ContinuousAudioScriptRevision> saved = new LinkedHashMap<>();
    private ContinuousAudioQueue queue;

    @BeforeEach void setUp() {
        var command = new ContinuousAudioService.QueueCommand("月亮", 9L, 3, 1, "一般听众", "voice", 1, null, "CONTINUOUS");
        queue = new ContinuousAudioQueue(id, 1L, 9L, null, "hash", json.writeValueAsString(command), "prepare", Instant.now().plusSeconds(60));
        queue.prepared("月亮", "[]", 0L);
        var segment = new ContinuousAudioSegment(id, 1, ORIGINAL, Hashing.sha256(ORIGINAL), UUID.randomUUID().toString());
        segment.ready("/media/" + segment.getAudioId() + ".wav", 41L);
        live.add(segment);
        when(queues.findLockedById(id)).thenReturn(Optional.of(queue));
        when(projects.existsByIdAndUserId(9L, 1L)).thenReturn(true);
        when(segments.findByQueueIdOrderBySegmentNumberAsc(id)).thenAnswer(call -> new ArrayList<>(live));
        when(segments.findByQueueIdAndSegmentNumber(eq(id), anyInt())).thenAnswer(call -> live.stream()
                .filter(value -> value.getSegmentNumber() == (int) call.getArgument(1)).findFirst());
        when(segments.save(any())).thenAnswer(call -> { live.add(call.getArgument(0)); return call.getArgument(0); });
        doAnswer(call -> { live.remove(call.getArgument(0)); return null; }).when(segments).delete(any());
        when(proposals.saveAndFlush(any())).thenAnswer(call -> {
            ContinuousAudioScriptRevision proposal = call.getArgument(0); saved.put(proposal.getId(), proposal); return proposal;
        });
        when(proposals.findTop30ByQueueIdOrderByCreatedAtDesc(id)).thenAnswer(call -> values().stream().limit(30).toList());
        when(proposals.findTop20ByQueueIdAndStatusOrderByCreatedAtDesc(eq(id), anyString())).thenAnswer(call -> values().stream()
                .filter(value -> value.getStatus().equals(call.getArgument(1))).limit(20).toList());
        when(proposals.countByQueueIdAndStatus(eq(id), anyString())).thenAnswer(call -> values().stream()
                .filter(value -> value.getStatus().equals(call.getArgument(1))).count());
        when(proposals.findByIdAndQueueIdAndUserId(anyString(), eq(id), eq(1L)))
                .thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        when(proposals.findByUserIdAndRequestKey(eq(1L), anyString())).thenAnswer(call -> saved.values().stream()
                .filter(value -> Objects.equals(value.getRequestKey(), call.getArgument(1))).findFirst());
        store.setScriptRevisions(proposals);
    }
    private List<ContinuousAudioScriptRevision> values() { return saved.values().stream()
            .sorted(Comparator.comparing(ContinuousAudioScriptRevision::getCreatedAt).reversed()).toList(); }
    private String stage(String replacement) {
        var result = store.proposeScriptRevision(1L, id, "REVISE", 1, List.of(replacement), "改得自然一点",
                store.scriptRevision(1L, id, 1), () -> { });
        return String.valueOf(((Map<?, ?>) result.get("script_revision")).get("id"));
    }

    @Test void stagingIsDurableButLeavesTheLiveScriptAndAudioUntouched() {
        String audio = live.get(0).getAudioId();
        String proposal = stage(EDITED);
        assertThat(saved.get(proposal).getStatus()).isEqualTo("PENDING");
        assertThat(saved.get(proposal).getBeforeText()).isEqualTo(ORIGINAL);
        assertThat(saved.get(proposal).getAfterText()).isEqualTo(EDITED);
        assertThat(live.get(0).getScript()).isEqualTo(ORIGINAL);
        assertThat(live.get(0).getAudioId()).isEqualTo(audio);
        assertThat(live.get(0).getStatus()).isEqualTo("READY");
        assertThat(store.scriptRevisions(1L, id)).hasSize(1);
        verify(records, never()).deleteProjectRecord(anyLong());
    }

    @Test void rejectingAProposalPreservesBothOriginalScriptAndAudio() {
        String audio = live.get(0).getAudioId();
        String proposal = stage(EDITED);
        var decision = store.decideScriptRevision(1L, id, proposal, true, false);
        assertThat(decision.applied()).isFalse();
        assertThat(saved.get(proposal).getStatus()).isEqualTo("REJECTED");
        assertThat(live.get(0).getScript()).isEqualTo(ORIGINAL);
        assertThat(live.get(0).getAudioId()).isEqualTo(audio);
        assertThat(store.decideScriptRevision(1L, id, proposal, true, false).applied()).isFalse();
        verify(records, never()).deleteProjectRecord(anyLong());
    }

    @Test void acceptingIsExplicitAndAppliesExactlyOnce() {
        String proposal = stage(EDITED);
        assertThatThrownBy(() -> store.decideScriptRevision(1L, id, proposal, false, true)).isInstanceOf(ApiException.class);
        assertThat(live.get(0).getScript()).isEqualTo(ORIGINAL);
        var decision = store.decideScriptRevision(1L, id, proposal, true, true);
        String newAudio = live.get(0).getAudioId();
        assertThat(decision.applied()).isTrue();
        assertThat(decision.firstSegment()).isEqualTo(1);
        assertThat(saved.get(proposal).getStatus()).isEqualTo("ACCEPTED");
        assertThat(live.get(0).getScript()).isEqualTo(EDITED);
        assertThat(live.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(store.decideScriptRevision(1L, id, proposal, true, true).applied()).isFalse();
        assertThat(live.get(0).getAudioId()).isEqualTo(newAudio);
        verify(records, times(1)).deleteProjectRecord(41L);
    }

    @Test void concurrentChangesMakeTheProposalStaleWithoutOverwritingCurrentText() {
        String proposal = stage(EDITED);
        String concurrent = "别的窗口修改后的中文正文，以这份为当前版本。";
        live.get(0).revise(concurrent, Hashing.sha256(concurrent), UUID.randomUUID().toString());
        var decision = store.decideScriptRevision(1L, id, proposal, true, true);
        assertThat(decision.stale()).isTrue();
        assertThat(decision.applied()).isFalse();
        assertThat(saved.get(proposal).getStatus()).isEqualTo("STALE");
        assertThat(live.get(0).getScript()).isEqualTo(concurrent);
        verify(records, never()).deleteProjectRecord(anyLong());
    }

    @Test void proposalsAndDecisionsCannotCrossOwnersOrQueues() {
        String proposal = stage(EDITED);
        assertThatThrownBy(() -> store.decideScriptRevision(2L, id, proposal, true, true))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> store.decideScriptRevision(1L, "other", proposal, true, true)).isInstanceOf(ApiException.class);
        assertThat(saved.get(proposal).getStatus()).isEqualTo("PENDING");
    }

    @Test void rollbackIsAnotherProposalUntilTheUserAcceptsIt() {
        String proposal = stage(EDITED);
        store.decideScriptRevision(1L, id, proposal, true, true);
        var rollback = store.revertScriptRevision(1L, id, proposal, true);
        String inverse = String.valueOf(((Map<?, ?>) rollback.get("script_revision")).get("id"));
        assertThat(saved.get(inverse).getBeforeText()).isEqualTo(EDITED);
        assertThat(saved.get(inverse).getAfterText()).isEqualTo(ORIGINAL);
        assertThat(live.get(0).getScript()).isEqualTo(EDITED);
        store.decideScriptRevision(1L, id, inverse, true, true);
        assertThat(live.get(0).getScript()).isEqualTo(ORIGINAL);
    }

    @Test void rollbackRefusesToDiscardChangesMadeAfterAcceptance() {
        String proposal = stage(EDITED);
        store.decideScriptRevision(1L, id, proposal, true, true);
        live.get(0).revise("后来新增的一段正文，不允许被老回退覆盖。", "newhash", UUID.randomUUID().toString());
        assertThatThrownBy(() -> store.revertScriptRevision(1L, id, proposal, true)).isInstanceOf(ApiException.class)
                .hasMessageContaining("又发生了变化");
        assertThat(saved).hasSize(1);
    }

    @Test void repeatedPendingRollbackIsSharedButARejectedRollbackCanBeProposedAgain() {
        String proposal = stage(EDITED);
        store.decideScriptRevision(1L, id, proposal, true, true);
        var first = (Map<?, ?>) store.revertScriptRevision(1L, id, proposal, true).get("script_revision");
        var duplicate = (Map<?, ?>) store.revertScriptRevision(1L, id, proposal, true).get("script_revision");
        assertThat(duplicate.get("id")).isEqualTo(first.get("id"));
        store.decideScriptRevision(1L, id, String.valueOf(first.get("id")), true, false);
        var fresh = (Map<?, ?>) store.revertScriptRevision(1L, id, proposal, true).get("script_revision");
        assertThat(fresh.get("id")).isNotEqualTo(first.get("id"));
        assertThat(fresh.get("status")).isEqualTo("PENDING");
        assertThat(live.get(0).getScript()).isEqualTo(EDITED);
    }

    @Test void extensionDoesNotAppendUntilAcceptedAndCanBeRolledBackExactly() {
        String addition = "这是新增的内容，我们接着描述月球的运行轨迹。";
        var result = store.proposeScriptRevision(1L, id, "EXTEND", null, List.of(addition), "继续讲",
                store.fullScriptRevision(1L, id), () -> { });
        String proposal = String.valueOf(((Map<?, ?>) result.get("script_revision")).get("id"));
        assertThat(live).hasSize(1);
        var decision = store.decideScriptRevision(1L, id, proposal, true, true);
        assertThat(decision.firstSegment()).isEqualTo(2);
        assertThat(live).extracting(ContinuousAudioSegment::getScript).containsExactly(ORIGINAL, addition);
        var rollback = store.revertScriptRevision(1L, id, proposal, true);
        String inverse = String.valueOf(((Map<?, ?>) rollback.get("script_revision")).get("id"));
        store.decideScriptRevision(1L, id, inverse, true, true);
        assertThat(live).extracting(ContinuousAudioSegment::getScript).containsExactly(ORIGINAL);
    }

    @Test void stagingAndRejectingPreserveAnExistingSynthesisFailure() {
        queue.fail("NEEDS_REVIEW", "原有合成结果未知，需要核验");
        String proposal = stage(EDITED);
        assertThat(queue.getLastError()).contains("原有合成结果未知");
        store.decideScriptRevision(1L, id, proposal, true, false);
        assertThat(queue.getLastError()).contains("原有合成结果未知");
    }

    @Test void keyedRequestsRemainIdempotentAfterTheProposalWasRejected() {
        var result = store.proposeScriptRevision(1L, id, "REVISE", 1, List.of(EDITED), "自然一点",
                store.scriptRevision(1L, id, 1), () -> { }, "request-1");
        String proposal = String.valueOf(((Map<?, ?>) result.get("script_revision")).get("id"));
        store.decideScriptRevision(1L, id, proposal, true, false);
        var replay = store.proposeScriptRevision(1L, id, "REVISE", 1, List.of(EDITED), "自然一点",
                store.scriptRevision(1L, id, 1), () -> { }, "request-1");
        assertThat(((Map<?, ?>) replay.get("script_revision")).get("id")).isEqualTo(proposal);
        assertThat(saved).hasSize(1);
    }

    @Test void keyedNoChangeRemainsClosedAndStableAfterTheScriptChangesLater() {
        var baseline = store.scriptRevision(1L, id, 1);
        var result = store.proposeScriptRevision(1L, id, "REVISE", 1, List.of(ORIGINAL), "保持原样",
                baseline, () -> { }, "no-change-request");
        var proposal = (Map<?, ?>) result.get("script_revision");
        assertThat(proposal.get("status")).isEqualTo("UNCHANGED");
        live.get(0).revise(EDITED, Hashing.sha256(EDITED), UUID.randomUUID().toString());
        var replay = store.proposeScriptRevision(1L, id, "REVISE", 1, List.of(ORIGINAL), "保持原样",
                baseline, () -> { }, "no-change-request");
        assertThat(((Map<?, ?>) replay.get("script_revision")).get("id")).isEqualTo(proposal.get("id"));
        assertThat(live.get(0).getScript()).isEqualTo(EDITED);
        assertThat(saved).hasSize(1);
        verify(records, never()).deleteProjectRecord(anyLong());
    }

    @Test void aDeletedTargetSegmentMakesItsPendingProposalStale() {
        String proposal = stage(EDITED);
        live.clear();
        var decision = store.decideScriptRevision(1L, id, proposal, true, true);
        assertThat(decision.stale()).isTrue();
        assertThat(saved.get(proposal).getStatus()).isEqualTo("STALE");
        assertThat(live).isEmpty();
    }

    @Test void allPendingProposalsRemainVisibleEvenBehindNewerClosedHistory() {
        String oldest = stage(EDITED);
        for (int i = 0; i < 35; i++) {
            var result = store.proposeScriptRevision(1L, id, "REVISE", 1, List.of(EDITED + i), "自然一点",
                    store.scriptRevision(1L, id, 1), () -> { }, "request-" + i);
            String revision = String.valueOf(((Map<?, ?>) result.get("script_revision")).get("id"));
            store.decideScriptRevision(1L, id, revision, true, false);
        }
        var visible = store.scriptRevisions(1L, id);
        assertThat(visible).hasSize(30);
        assertThat(visible).anySatisfy(value -> { assertThat(value.get("id")).isEqualTo(oldest);
            assertThat(value.get("status")).isEqualTo("PENDING"); });
    }

    @Test void keyedRequestsDoNotBorrowAnotherRequestsUnkeyedProposal() {
        String unkeyed = stage(EDITED);
        var keyed = store.proposeScriptRevision(1L, id, "REVISE", 1, List.of(EDITED), "自然一点",
                store.scriptRevision(1L, id, 1), () -> { }, "distinct-request");
        assertThat(((Map<?, ?>) keyed.get("script_revision")).get("id")).isNotEqualTo(unkeyed);
        assertThat(saved).hasSize(2);
    }

    @Test void chatUndoAfterAWholeRewriteProposesTheImmediatelyPreviousVersion() {
        String manual = stage(EDITED);
        store.decideScriptRevision(1L, id, manual, true, true);
        String wholeText = "整篇重写后的中文正文，新的结构和例子都已经改变。";
        var whole = store.proposeScriptRevision(1L, id, "REWRITE", null, List.of(wholeText), "重新安排结构",
                store.fullScriptRevision(1L, id), () -> { });
        String wholeId = String.valueOf(((Map<?, ?>) whole.get("script_revision")).get("id"));
        store.decideScriptRevision(1L, id, wholeId, true, true);
        var service = new ContinuousAudioService(mock(com.bedouyu.audiorag.agent.audio.AudioAgent.class),
                mock(com.bedouyu.audiorag.tools.audio.SpeechService.class), store,
                mock(org.springframework.data.redis.core.StringRedisTemplate.class), json,
                java.util.concurrent.Executors.newSingleThreadExecutor());
        try {
            var result = service.undoRevision(1L, id, 1);
            var proposal = (Map<?, ?>) result.get("script_revision");
            assertThat(proposal.get("before_text")).isEqualTo(wholeText);
            assertThat(proposal.get("after_text")).isEqualTo(EDITED);
            assertThat(proposal.get("status")).isEqualTo("PENDING");
            assertThat(live.get(0).getScript()).isEqualTo(wholeText);
            assertThat(live.get(0).getAuditJson()).contains(ORIGINAL, EDITED);
        } finally {
            service.close();
        }
    }

    @Test void unchangedWholeReplacementDoesNotCreateAnUndoEntryOrReplaceAudio() {
        String manual = stage(EDITED);
        store.decideScriptRevision(1L, id, manual, true, true);
        String audit = live.get(0).getAuditJson();
        String audio = live.get(0).getAudioId();
        var result = store.replaceScripts(1L, id, List.of(EDITED));
        assertThat(result.get("retired")).isEqualTo(List.of());
        assertThat(live.get(0).getAuditJson()).isEqualTo(audit);
        assertThat(live.get(0).getAudioId()).isEqualTo(audio);
        assertThat(store.previousScript(1L, id, 1)).contains(ORIGINAL);
    }
}
