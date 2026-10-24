package com.bedouyu.audiorag.tools.audio;

import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.service.ContinuousAudioService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerateAudioToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ContinuousAudioService service = mock(ContinuousAudioService.class);

    @Test void omittingSegmentsStartsOnlyTheNextPendingSegment() {
        var result = execute("{}", 3);
        verify(service).refill(1, "queue", 1);
        assertThat(result).containsEntry("started", 1);
    }

    @Test void explicitZeroRequestsAllRemainingSegments() {
        var result = execute("{\"segments\":0}", 3);
        verify(service).refill(1, "queue", 3);
        assertThat(result).containsEntry("started", 3);
    }

    @Test void aCompletedQueueDoesNotStartAnotherGeneration() {
        assertThat(execute("{}", 0)).containsEntry("started", 0);
        verify(service, never()).refill(anyLong(), anyString(), anyInt());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String args, int pending) {
        var provider = (ObjectProvider<ContinuousAudioService>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        var target = mock(AudioToolTarget.class);
        when(target.resolve(any(), anyString())).thenReturn("queue");
        List<Map<String, String>> segments = new ArrayList<>();
        for (int index = 0; index < pending; index++) segments.add(Map.of("status", "PENDING"));
        when(service.status(1, "queue")).thenReturn(Map.of("status", "READY", "segments", segments));
        when(service.refill(eq(1L), eq("queue"), anyInt())).thenReturn(Map.of("queue_id", "queue", "status", "GENERATING"));
        var context = new AgentExecutionContext("run", "session", "supervisor", 1, 7,
                new AgentBudget(40, 48, Duration.ofSeconds(5)), new EvidenceLedger(), null, null, ignored -> { });
        return (Map<String, Object>) new GenerateAudioTool(provider, target).execute(json.readTree(args), context);
    }
}
