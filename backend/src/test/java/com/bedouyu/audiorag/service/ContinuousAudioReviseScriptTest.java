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

    @Test void segmentScriptReturnsTheCurrentText() {
        Fixture fixture = fixture();

        assertThat(store.segmentScript(1, fixture.queue().getId(), 1)).isEqualTo(fixture.segment().getScript());
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
