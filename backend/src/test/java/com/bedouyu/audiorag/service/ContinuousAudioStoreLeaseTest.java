package com.bedouyu.audiorag.service;

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
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContinuousAudioStoreLeaseTest {
    private final ContinuousAudioQueueRepository queues = mock(ContinuousAudioQueueRepository.class);
    private final ContinuousAudioSegmentRepository segments = mock(ContinuousAudioSegmentRepository.class);
    private final LearningProjectRepository projects = mock(LearningProjectRepository.class);
    private final AudioRagRepository records = mock(AudioRagRepository.class);
    private final LearningPlanService learning = mock(LearningPlanService.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test void originalConstructorPreservesFiveMinuteWindowsLease() {
        Fixture fixture = fixture();
        var store = new ContinuousAudioStore(queues, segments, projects, records, learning, json);
        Instant before = Instant.now();
        store.claimGeneration(1, fixture.queue().getId()).orElseThrow();
        assertThat(fixture.queue().getLeaseUntil()).isBetween(before.plusSeconds(300), Instant.now().plusSeconds(300));
    }

    @Test void configuredLeaseCoversQueueClaimSegmentClaimAndCompletionRenewal() {
        Fixture fixture = fixture();
        var store = new ContinuousAudioStore(queues, segments, projects, records, learning, json, 1500);
        Instant before = Instant.now();
        String token = store.claimGeneration(1, fixture.queue().getId()).orElseThrow();
        assertThat(fixture.queue().getLeaseUntil()).isBetween(before.plusSeconds(1500), Instant.now().plusSeconds(1500));

        fixture.queue().renew(Instant.now().plusSeconds(30));
        before = Instant.now();
        var claim = store.claimNext(1, fixture.queue().getId(), token).orElseThrow();
        assertThat(fixture.segment().getLeaseUntil()).isBetween(before.plusSeconds(1500), Instant.now().plusSeconds(1500));
        assertThat(fixture.queue().getLeaseUntil()).isEqualTo(fixture.segment().getLeaseUntil());

        fixture.queue().renew(Instant.now().plusSeconds(30));
        before = Instant.now();
        String audioUrl = "/media/" + fixture.segment().getAudioId() + ".wav";
        store.complete(1, claim, audioUrl);
        assertThat(fixture.segment().getStatus()).isEqualTo("READY");
        assertThat(fixture.segment().getAudioUrl()).isEqualTo(audioUrl);
        assertThat(fixture.queue().getLeaseUntil()).isBetween(before.plusSeconds(1500), Instant.now().plusSeconds(1500));
    }

    @Test void extendedLeaseStillRequiresReviewWhenItActuallyExpires() {
        Fixture fixture = fixture();
        var store = new ContinuousAudioStore(queues, segments, projects, records, learning, json, 1500);
        String token = store.claimGeneration(1, fixture.queue().getId()).orElseThrow();
        store.claimNext(1, fixture.queue().getId(), token).orElseThrow();
        Instant expired = Instant.now().minusSeconds(1);
        fixture.queue().renew(expired);
        fixture.segment().claim(token, expired);
        assertThat(store.status(1, fixture.queue().getId())).containsEntry("status", "NEEDS_REVIEW");
        assertThat(fixture.segment().getStatus()).isEqualTo("NEEDS_REVIEW");
        assertThatThrownBy(() -> store.claimGeneration(1, fixture.queue().getId())).hasMessageContaining("不能自动继续");
        verifyNoInteractions(records, learning);
    }

    @Test void invalidLeaseConfigurationFailsAtStartup() {
        for (int seconds : new int[]{0, -1})
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new ContinuousAudioStore(queues, segments, projects, records, learning, json, seconds));
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
