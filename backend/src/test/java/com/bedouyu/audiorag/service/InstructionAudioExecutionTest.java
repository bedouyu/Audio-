package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.agent.audio.AudioAgent.CoursePlan;
import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.service.ContinuousAudioService.QueueCommand;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises dispatch/preparation boundaries without running an external model or speech provider. */
class InstructionAudioExecutionTest {
    AudioAgent agent = mock(AudioAgent.class);
    SpeechService speech = mock(SpeechService.class);
    ContinuousAudioStore store = mock(ContinuousAudioStore.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ExecutorService worker = mock(ExecutorService.class);
    ContinuousAudioService service;
    String id = UUID.randomUUID().toString();
    ContinuousAudioStore.Creation creation = new ContinuousAudioStore.Creation(id, "preparation", true);
    ArgumentCaptor<Runnable> work = ArgumentCaptor.forClass(Runnable.class);

    @BeforeEach void setup() {
        service = new ContinuousAudioService(agent, speech, store, redis, new ObjectMapper(), worker);
        when(worker.submit(work.capture())).thenReturn(CompletableFuture.completedFuture(null));
        when(store.statusIfPresent(1, id)).thenReturn(Optional.of(Map.of("queue_id", id, "version", 1, "status", "PREPARING")));
        when(store.claimGeneration(1, id)).thenReturn(Optional.empty());
    }
    @AfterEach void close() { service.close(); }

    @Test void instructionReturnsBeforeModelPreparationAndReplayDoesNotDispatchAnotherJob() {
        var command = command("写一个原创故事", null, Map.of("language", "zh"));
        assertThat(service.startInstruction(1, command, creation)).containsEntry("status", "PREPARING");
        verifyNoInteractions(agent, speech);
        when(agent.createCourse(eq(1L), any())).thenReturn(new CoursePlan("故事", "first paragraph\n\nsecond paragraph", List.of(), 0));
        work.getValue().run();
        verify(store).prepared(1, creation, new CoursePlan("故事", "first paragraph\n\nsecond paragraph", List.of(), 0),
                List.of("first paragraph\n\nsecond paragraph"));
        service.startInstruction(1, command, new ContinuousAudioStore.Creation(id, null, false));
        verify(worker, times(1)).submit(any(Runnable.class)); verify(agent, times(1)).createCourse(eq(1L), any());
    }

    @Test void exactOriginalSplitsWithoutDroppingWhitespaceOrSplittingUnicodeAndNeverCreatesBlankSegments() {
        String original = " \n".repeat(1000) + "Chapter 1\n" + "A😀字".repeat(700) + "\n ".repeat(1000);
        var context = new HashMap<String, Object>(Map.of("language", "zh", "exact_source", true,
                "source_document_id", "doc", "source_start", 0, "source_end", original.length()));
        var command = command("朗读这本书", original, context);
        when(agent.createCourse(eq(1L), any())).thenReturn(new CoursePlan("原文", original, List.of(), 0));
        service.startInstruction(1, command, creation); work.getValue().run();
        @SuppressWarnings("unchecked") ArgumentCaptor<List<String>> parts = ArgumentCaptor.forClass(List.class);
        verify(store).prepared(eq(1L), eq(creation), any(), parts.capture());
        assertThat(String.join("", parts.getValue())).isEqualTo(original);
        assertThat(parts.getValue()).allSatisfy(piece -> {
            assertThat(piece.isBlank()).isFalse();
            assertThat(Character.isLowSurrogate(piece.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(piece.charAt(piece.length() - 1))).isFalse();
        });
        verify(agent).createCourse(eq(1L), argThat(c -> c.sourceText().equals(original) && c.contextScript() == null));
        verifyNoInteractions(speech);
    }

    @Test void selectedOriginalForSummaryIsPassedAsDataInsteadOfExactReadAndKeepsSourceProvenance() {
        var context = new HashMap<String, Object>(Map.of("language", "en", "exact_source", false,
                "source_document_id", "doc", "source_start", 20, "source_end", 80, "source_total", 200));
        var command = command("用英语总结这份资料", "Selected exact original excerpt", context);
        when(agent.createCourse(eq(1L), any())).thenReturn(new CoursePlan("Summary", "A new English summary.",
                List.of(Map.of("title", "Fresh reference", "source", "https://example.org/current")), 0));
        service.startInstruction(1, command, creation); work.getValue().run();
        verify(agent).createCourse(eq(1L), argThat(c -> c.sourceText() == null && c.contextScript().contains("Selected exact original excerpt")
                && c.contextScript().contains("20 到 80") && c.instruction().equals("用英语总结这份资料")));
        verify(store).prepared(eq(1L), eq(creation), argThat(c -> c.citations().size() == 2
                && c.citations().stream().anyMatch(source -> "doc".equals(source.get("source_document_id")))
                && c.citations().stream().anyMatch(source -> "https://example.org/current".equals(source.get("source")))), anyList());
    }

    @Test void rewriteUsesTheSelectedPreviousQueueAndRetainsUnreverifiedNewsSources() {
        var context = Map.<String, Object>of("language", "en", "previous_queue_id", "selected-old-queue");
        when(store.scriptForUser(1, "selected-old-queue")).thenReturn("The exact selected previous script.");
        when(store.status(1, "selected-old-queue")).thenReturn(Map.of("citations", List.of(
                Map.of("title", "Original report", "published_at", "2026-10-20T08:00:00Z", "source", "https://example.org/report"))));
        when(agent.createCourse(eq(1L), any())).thenReturn(new CoursePlan("Shorter", "The shorter English version.", List.of(), 0));
        service.startInstruction(1, command("缩短刚才的内容", null, context), creation); work.getValue().run();
        verify(agent).createCourse(eq(1L), argThat(c -> "The exact selected previous script.".equals(c.contextScript())));
        verify(store).prepared(eq(1L), eq(creation), argThat(c -> c.citations().size() == 1
                && Boolean.FALSE.equals(c.citations().get(0).get("reverified"))
                && "selected-old-queue".equals(c.citations().get(0).get("adapted_from_queue"))), anyList());
    }

    @Test void unsupportedVoiceStopsBeforeCallingTheModelAndRecordsFailedPreparation() {
        when(speech.selectVoice(any(), eq("en"))).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "没有英文音色"));
        service.startInstruction(1, command("英文故事", null, Map.of("language", "en")), creation); work.getValue().run();
        verify(store).preparationFailed(1, creation, "没有英文音色"); verifyNoInteractions(agent);
        verify(store, never()).claimGeneration(anyLong(), anyString()); verify(store, never()).prepared(anyLong(), any(), any(), anyList());
    }

    @Test void saturatedDispatchRecordsFailureAndNeverRunsModelOrSpeech() {
        when(worker.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("busy"));
        assertThatThrownBy(() -> service.startInstruction(1, command("故事", null, Map.of()), creation)).isInstanceOf(ApiException.class);
        verify(store).preparationFailed(eq(1L), eq(creation), contains("已满")); verifyNoInteractions(agent, speech);
    }

    private QueueCommand command(String instruction, String source, Map<String, Object> context) {
        return new QueueCommand("topic", 2, 5, 3, null, null, 2, UUID.randomUUID().toString(), "CONTINUOUS",
                instruction, source, source == null ? null : "原文", context);
    }
}
