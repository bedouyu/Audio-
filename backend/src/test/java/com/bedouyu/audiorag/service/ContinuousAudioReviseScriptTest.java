package com.bedouyu.audiorag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.model.entity.ContinuousAudioQueue;
import com.bedouyu.audiorag.model.entity.ContinuousAudioSegment;
import com.bedouyu.audiorag.repository.*;
import com.bedouyu.audiorag.service.ContinuousAudioService.QueueCommand;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/** 在当前讲稿基础上改一段：只换这一段，旧稿与旧音频进审计与延迟清理。 */
class ContinuousAudioReviseScriptTest {
    private final ContinuousAudioQueueRepository queues = mock(ContinuousAudioQueueRepository.class);
    private final ContinuousAudioSegmentRepository segments = mock(ContinuousAudioSegmentRepository.class);
    private final LearningProjectRepository projects = mock(LearningProjectRepository.class);
    private final AudioRagRepository records = mock(AudioRagRepository.class);
    private final LearningPlanService learning = mock(LearningPlanService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ContinuousAudioStore store =
            new ContinuousAudioStore(queues, segments, projects, records, learning, json);

    @Test void revisingASegmentReplacesItsScriptAndAudioButKeepsTheRest() {
        Fixture fixture = fixture();
        fixture.segment().ready("/media/" + fixture.segment().getAudioId() + ".wav", 42L);
        String oldScript = fixture.segment().getScript();
        String oldAudioId = fixture.segment().getAudioId();

        var outcome = store.reviseScript(1, fixture.queue().getId(), 1, "改得更口语一些，保留原来的例子。", "改口语");

        assertThat(fixture.segment().getScript()).isEqualTo("改得更口语一些，保留原来的例子。");
        assertThat(fixture.segment().getScriptHash()).isEqualTo(Hashing.sha256("改得更口语一些，保留原来的例子。"));
        assertThat(fixture.segment().getAudioId()).isNotEqualTo(oldAudioId);
        assertThat(fixture.segment().getStatus()).isEqualTo("PENDING");
        assertThat(fixture.segment().getAudioUrl()).isNull();
        assertThat(outcome.get("retired")).isEqualTo(List.of(oldAudioId));
        assertThat(fixture.segment().getAuditJson()).contains(oldScript).contains("REVISE_SCRIPT");
        // 同一条目重制：旧记录删掉，合成完成后重新写一条。
        verify(records).deleteProjectRecord(42L);
    }

    @Test void revisingIsRefusedWhileSynthesisIsRunning() {
        Fixture fixture = fixture();
        fixture.segment().claim("token", Instant.now().plusSeconds(600));

        assertThatThrownBy(() -> store.reviseScript(1, fixture.queue().getId(), 1, "新讲稿内容在这里。", "改"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("正在合成");
    }

    @Test void revisingIsRefusedWhileTheQueueIsGenerating() {
        Fixture fixture = fixture();
        fixture.queue().claim("run", Instant.now().plusSeconds(600));

        assertThatThrownBy(() -> store.reviseScript(1, fixture.queue().getId(), 1, "新讲稿内容在这里。", "改"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("正在生成");
        verify(records, never()).deleteProjectRecord(anyLong());
    }

    @Test void previousScriptReturnsTheTextBeforeTheLastRevision() {
        Fixture fixture = fixture();
        String original = fixture.segment().getScript();
        store.reviseScript(1, fixture.queue().getId(), 1, "第一版改稿的文字内容。", "第一版");
        store.reviseScript(1, fixture.queue().getId(), 1, "第二版改稿的文字内容。", "第二版");

        assertThat(store.previousScript(1, fixture.queue().getId(), 1)).contains("第一版改稿的文字内容。");
        assertThat(store.previousScript(1, fixture.queue().getId(), 1)).isNotEqualTo(java.util.Optional.of(original));
    }

    @Test void previousScriptIsEmptyWithoutAnyRevision() {
        Fixture fixture = fixture();

        assertThat(store.previousScript(1, fixture.queue().getId(), 1)).isEmpty();
    }
    @Test void segmentScriptReturnsTheCurrentText() {
        Fixture fixture = fixture();

        assertThat(store.segmentScript(1, fixture.queue().getId(), 1)).isEqualTo(fixture.segment().getScript());
    }

    @Test void replaceScriptsRewritesEverySegmentInPlaceAndResetsAudio() {
        Fixture fixture = fixture();
        fixture.segment().ready("/media/" + fixture.segment().getAudioId() + ".wav", 42L);
        String oldAudioId = fixture.segment().getAudioId();

        var outcome = store.replaceScripts(1, fixture.queue().getId(),
                List.of("第一段重写后的内容。", "第二段重写后的内容。"));

        List<ContinuousAudioSegment> saved = org.mockito.ArgumentCaptor
                .forClass(ContinuousAudioSegment.class).getAllValues() == null ? List.of() : List.of();
        assertThat(fixture.segment().getScript()).isEqualTo("第一段重写后的内容。");
        assertThat(fixture.segment().getStatus()).isEqualTo("PENDING");
        assertThat(fixture.segment().getAudioId()).isNotEqualTo(oldAudioId);
        assertThat(outcome.get("segments")).isEqualTo(2);
        assertThat(outcome.get("retired").toString()).contains(oldAudioId);
        verify(segments).save(any(ContinuousAudioSegment.class));
    }

    @Test void fullScriptJoinsSegments() {
        Fixture fixture = fixture();

        assertThat(store.fullScript(1, fixture.queue().getId())).contains("A short script.");
    }

    @Test void replaceScriptsRejectsEmptyContent() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> store.replaceScripts(1, fixture.queue().getId(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void splitIntoKeepsSentenceBoundariesAndPageCount() {
        String text = "第一句。第二句。第三句。第四句。";

        List<String> parts = ContinuousAudioService.splitInto(text, 2);

        assertThat(parts).hasSize(2);
        assertThat(String.join("", parts)).isEqualTo(text);
        assertThat(parts).allSatisfy(part -> assertThat(part).endsWith("。"));
    }

    @Test void splitIntoPreservesEnglishParagraphSeparatorsInsideAPage() {
        String text = "First paragraph has no punctuation\n\nSecond paragraph continues the sentence"
                + "\n\nThird paragraph also has words\n\nFourth paragraph finishes the example";

        List<String> parts = ContinuousAudioService.splitInto(text, 2);

        assertThat(parts).hasSize(2);
        assertThat(String.join("\n\n", parts)).isEqualTo(text);
    }
    @Test void appendScriptsKeepsExistingSegmentsAndNumbersNewOnes() {
        Fixture fixture = fixture();
        fixture.segment().ready("/media/a.wav", 41L);
        String original = fixture.segment().getScript();

        var outcome = store.appendScripts(1, fixture.queue().getId(), List.of("新增的一段内容。", "新增的第二段。"));

        assertThat(fixture.segment().getScript()).isEqualTo(original);
        assertThat(fixture.segment().getStatus()).isEqualTo("READY");
        assertThat(outcome.get("added")).isEqualTo(2);
        assertThat(outcome.get("start")).isEqualTo(2);
        verify(segments, times(2)).save(any(ContinuousAudioSegment.class));
    }

    @Test void staleSegmentRevisionCannotOverwriteANewerEditOrRetireItsAudio() {
        Fixture fixture = fixture();
        var baseline = store.scriptRevision(1, fixture.queue().getId(), 1);
        store.reviseScript(1, fixture.queue().getId(), 1, "另一处先保存的新讲稿。", "先保存");
        String winnerAudioId = fixture.segment().getAudioId();
        String winnerAudit = fixture.segment().getAuditJson();

        assertThatThrownBy(() -> store.reviseScript(1, fixture.queue().getId(), 1,
                "耗时模型根据旧稿返回的内容。", "迟到", baseline, () -> { }))
                .isInstanceOfSatisfying(ApiException.class, failure ->
                        assertThat(failure.status()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(fixture.segment().getScript()).isEqualTo("另一处先保存的新讲稿。");
        assertThat(fixture.segment().getAudioId()).isEqualTo(winnerAudioId);
        assertThat(fixture.segment().getAuditJson()).isEqualTo(winnerAudit);
        verifyNoInteractions(records);
    }

    @Test void wholeRewriteRejectsAnAppendThatHappenedDuringModelGeneration() {
        Fixture fixture = fixture();
        var baseline = store.fullScriptRevision(1, fixture.queue().getId());
        var added = new ContinuousAudioSegment(fixture.queue().getId(), 2, "新增讲稿。",
                Hashing.sha256("新增讲稿。"), UUID.randomUUID().toString());
        when(segments.findByQueueIdOrderBySegmentNumberAsc(fixture.queue().getId()))
                .thenReturn(List.of(fixture.segment(), added));
        String oldAudio = fixture.segment().getAudioId();

        assertThatThrownBy(() -> store.replaceScripts(1, fixture.queue().getId(), List.of("迟到的整篇重写。"),
                baseline, () -> { })).isInstanceOf(ApiException.class).hasMessageContaining("版本已改变");

        assertThat(fixture.segment().getAudioId()).isEqualTo(oldAudio);
        assertThat(fixture.segment().getScript()).isEqualTo("A short script.");
        verify(segments, never()).delete(any());
        verify(segments, never()).save(any());
        verifyNoInteractions(records);
    }

    @Test void appendRejectsARevisionThatHappenedDuringModelGeneration() {
        Fixture fixture = fixture();
        var baseline = store.fullScriptRevision(1, fixture.queue().getId());
        store.reviseScript(1, fixture.queue().getId(), 1, "另一处先改了结尾。", "先保存");

        assertThatThrownBy(() -> store.appendScripts(1, fixture.queue().getId(), List.of("根据旧结尾续写。"),
                baseline, () -> { })).isInstanceOf(ApiException.class).hasMessageContaining("版本已改变");

        verify(segments, never()).save(any());
        assertThat(fixture.segment().getScript()).isEqualTo("另一处先改了结尾。");
    }

    @Test void cancelledAndPreparingQueuesCannotBeReactivatedByWholeEdits() {
        Fixture fixture = fixture();
        fixture.queue().release("CANCELLED");
        assertThatThrownBy(() -> store.appendScripts(1, fixture.queue().getId(), List.of("新增内容。")))
                .isInstanceOf(ApiException.class).hasMessageContaining("已取消");
        assertThatThrownBy(() -> store.replaceScripts(1, fixture.queue().getId(), List.of("重写内容。")))
                .isInstanceOf(ApiException.class).hasMessageContaining("已取消");
        assertThat(fixture.queue().getStatus()).isEqualTo("CANCELLED");
        fixture.queue().release("PREPARING");
        assertThatThrownBy(() -> store.appendScripts(1, fixture.queue().getId(), List.of("新增内容。")))
                .isInstanceOf(ApiException.class).hasMessageContaining("正在生成");
        assertThatThrownBy(() -> store.replaceScripts(1, fixture.queue().getId(), List.of("重写内容。")))
                .isInstanceOf(ApiException.class).hasMessageContaining("正在生成");
        assertThat(fixture.queue().getStatus()).isEqualTo("PREPARING");
        verify(segments, never()).save(any());
        verifyNoInteractions(records);
    }

    @Test void cancelledMutationGuardRunsBeforeChangingAnyScriptOrAudio() {
        Fixture fixture = fixture();
        var baseline = store.fullScriptRevision(1, fixture.queue().getId());
        String originalAudio = fixture.segment().getAudioId();
        Runnable stopped = () -> { throw new IllegalStateException("已停止"); };

        assertThatThrownBy(() -> store.replaceScripts(1, fixture.queue().getId(), List.of("不能保存的重写。"),
                baseline, stopped)).hasMessage("已停止");
        assertThatThrownBy(() -> store.appendScripts(1, fixture.queue().getId(), List.of("不能保存的续写。"),
                baseline, stopped)).hasMessage("已停止");
        var single = store.scriptRevision(1, fixture.queue().getId(), 1);
        assertThatThrownBy(() -> store.reviseScript(1, fixture.queue().getId(), 1, "不能保存的单段。", "改", single, stopped))
                .hasMessage("已停止");

        assertThat(fixture.segment().getScript()).isEqualTo("A short script.");
        assertThat(fixture.segment().getAudioId()).isEqualTo(originalAudio);
        verify(segments, never()).save(any());
        verifyNoInteractions(records);
    }

    @Test void scopedGenerationSkipsEarlierPendingSegments() {
        Fixture fixture = fixture();
        var later = new ContinuousAudioSegment(fixture.queue().getId(), 3, "目标讲稿。",
                Hashing.sha256("目标讲稿。"), UUID.randomUUID().toString());
        when(segments.findByQueueIdOrderBySegmentNumberAsc(fixture.queue().getId()))
                .thenReturn(List.of(fixture.segment(), later));
        fixture.queue().claim("run", Instant.now().plusSeconds(600));

        var claimed = store.claimNext(1, fixture.queue().getId(), "run", 3).orElseThrow();

        assertThat(claimed.segmentNumber()).isEqualTo(3);
        assertThat(fixture.segment().getStatus()).isEqualTo("PENDING");
        assertThat(later.getStatus()).isEqualTo("SYNTHESIZING");
    }

    @Test void pollingAQueueWithoutALeaseDoesNotAcquireAWriteLock() {
        Fixture fixture = fixture();
        clearInvocations(queues);

        store.status(1, fixture.queue().getId());

        verify(queues, never()).findLockedById(anyString());
    }

    @Test void projectScopedOwnershipRejectsAnotherProjectEvenForTheSameUser() {
        Fixture fixture = fixture();
        store.requireOwnedInProject(1, 7, fixture.queue().getId());

        assertThatThrownBy(() -> store.requireOwnedInProject(1, 8, fixture.queue().getId()))
                .isInstanceOfSatisfying(ApiException.class, failure ->
                        assertThat(failure.status()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(queues, never()).findLockedById(anyString());
    }

    private Fixture fixture() {
        String id = UUID.randomUUID().toString();
        var command = new QueueCommand("Topic", 7, 3, 1, "", "", 1, UUID.randomUUID().toString());
        var queue = new ContinuousAudioQueue(id, 1, 7, command.requestId(), "hash", json.writeValueAsString(command),
                "preparation", Instant.now().plusSeconds(600));
        queue.prepared("Topic", "[]", 0);
        String script = "A short script.";
        var segment = new ContinuousAudioSegment(id, 1, script, Hashing.sha256(script), UUID.randomUUID().toString());
        when(queues.findById(id)).thenReturn(Optional.of(queue));
        when(queues.findLockedById(id)).thenReturn(Optional.of(queue));
        when(projects.existsByIdAndUserId(7, 1)).thenReturn(true);
        when(segments.findByQueueIdOrderBySegmentNumberAsc(id)).thenReturn(List.of(segment));
        when(segments.findByQueueIdAndSegmentNumber(id, 1)).thenReturn(Optional.of(segment));
        return new Fixture(queue, segment);
    }

    private record Fixture(ContinuousAudioQueue queue, ContinuousAudioSegment segment) { }
}
