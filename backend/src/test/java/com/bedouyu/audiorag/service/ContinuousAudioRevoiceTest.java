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

/** 换音色：讲稿不变、音频编号重发、旧记录与旧音频交给后续清理。 */
class ContinuousAudioRevoiceTest {
    private final ContinuousAudioQueueRepository queues = mock(ContinuousAudioQueueRepository.class);
    private final ContinuousAudioSegmentRepository segments = mock(ContinuousAudioSegmentRepository.class);
    private final LearningProjectRepository projects = mock(LearningProjectRepository.class);
    private final AudioRagRepository records = mock(AudioRagRepository.class);
    private final LearningPlanService learning = mock(LearningPlanService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final ContinuousAudioStore store =
            new ContinuousAudioStore(queues, segments, projects, records, learning, json);

    @Test void revoiceRearmsEveryReadySegmentAndKeepsTheScript() {
        Fixture fixture = fixture();
        String oldAudioId = fixture.segment().getAudioId();
        String oldScript = fixture.segment().getScript();
        fixture.segment().ready("/media/" + oldAudioId + ".wav", 42L);

        var outcome = store.revoice(1, fixture.queue().getId(), "voice-b", commandJson("voice-b"));

        assertThat(fixture.segment().getStatus()).isEqualTo("PENDING");
        assertThat(fixture.segment().getAudioId()).isNotEqualTo(oldAudioId);
        assertThat(fixture.segment().getAudioUrl()).isNull();
        assertThat(fixture.segment().getScript()).isEqualTo(oldScript);
        assertThat(outcome.get("retired")).isEqualTo(List.of(oldAudioId));
        assertThat(outcome.get("segments")).isEqualTo(1);
        assertThat(fixture.queue().getCommandJson()).contains("voice-b");
        // 同一个条目重制：旧记录先删掉，合成完成后再写回一条，历史里不会出现重复。
        verify(records).deleteProjectRecord(42L);
    }

    @Test void revoiceIsRefusedWhileSynthesisIsRunning() {
        Fixture fixture = fixture();
        fixture.segment().ready("/media/" + fixture.segment().getAudioId() + ".wav", 1L);
        fixture.queue().claim("run-token", Instant.now().plusSeconds(600));

        assertThatThrownBy(() -> store.revoice(1, fixture.queue().getId(), "voice-b", commandJson("voice-b")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("正在生成");
        verify(records, never()).deleteProjectRecord(anyLong());
    }

    @Test void revoiceCanRestartSegmentsThatNeverStartedSynthesizing() {
        Fixture fixture = fixture();
        String oldAudioId = fixture.segment().getAudioId();

        // 服务中断会留下"READY + 全 PENDING"的队列：这种情况下也要能直接换音色重念。
        var outcome = store.revoice(1, fixture.queue().getId(), "voice-b", commandJson("voice-b"));

        assertThat(fixture.segment().getStatus()).isEqualTo("PENDING");
        assertThat(fixture.segment().getAudioId()).isNotEqualTo(oldAudioId);
        assertThat(outcome.get("segments")).isEqualTo(1);
        assertThat(outcome.get("retired")).isEqualTo(List.of(oldAudioId));
    }

    @Test void revoiceCanDiscardSegmentsWhoseResultIsUnknown() {
        Fixture fixture = fixture();
        fixture.segment().uncertain("上一次合成被拒绝");

        var outcome = store.revoice(1, fixture.queue().getId(), "voice-b", commandJson("voice-b"));

        // 用户明确要求换音色重念，未知结果被丢弃并重新排队。
        assertThat(fixture.segment().getStatus()).isEqualTo("PENDING");
        assertThat((List<?>) outcome.get("retired")).hasSize(1);
        assertThat(outcome.get("segments")).isEqualTo(1);
    }

    @Test void revoiceIsRefusedWhileASegmentIsBeingSynthesized() {
        Fixture fixture = fixture();
        fixture.segment().claim("segment-token", Instant.now().plusSeconds(600));

        assertThatThrownBy(() -> store.revoice(1, fixture.queue().getId(), "voice-b", commandJson("voice-b")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("正在合成");
    }

    private String commandJson(String voice) {
        return json.writeValueAsString(new QueueCommand("Topic", 7, 3, 1, "", voice, 1, UUID.randomUUID().toString()));
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
