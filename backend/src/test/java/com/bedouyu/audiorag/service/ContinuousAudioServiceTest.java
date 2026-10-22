package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.agent.audio.AudioAgent.CoursePlan;
import com.bedouyu.audiorag.agent.audio.AudioAgent.LessonDraft;
import com.bedouyu.audiorag.agent.audio.AudioAgent.LessonCommand;
import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.service.ContinuousAudioService.QueueCommand;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContinuousAudioServiceTest {
    @TempDir Path folder;
    AudioAgent agent;
    SpeechService speech;
    ContinuousAudioStore store;
    StringRedisTemplate redis;
    ValueOperations<String, String> values;
    ObjectMapper json;
    ContinuousAudioService service;
    final String queue = UUID.randomUUID().toString();
    final String audio = UUID.randomUUID().toString();
    final String hash = Hashing.sha256("script");
    @BeforeEach void setup() {
        agent = mock(AudioAgent.class); speech = mock(SpeechService.class); store = mock(ContinuousAudioStore.class);
        redis = mock(StringRedisTemplate.class); values = mock(ValueOperations.class); when(redis.opsForValue()).thenReturn(values);
        json = new ObjectMapper(); service = new ContinuousAudioService(agent, speech, store, redis, json);
    }
    @AfterEach void close() { service.close(); }

    @Test void replayReadsOriginalStatusWithoutCallingModelOrTts() {
        var command = command(); when(store.reserve(1, command)).thenReturn(new ContinuousAudioStore.Creation(queue, null, false));
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.of(snapshot()));
        assertThat(service.start(1, command)).containsEntry("queue_id", queue);
        verifyNoInteractions(agent, speech); verify(store, never()).claimGeneration(anyLong(), anyString());
    }

    @Test void newStartReservesBeforeCallingModelAndPersistsBeforeGeneration() {
        var command = command(); var creation = new ContinuousAudioStore.Creation(queue, "token", true);
        when(store.reserve(1, command)).thenReturn(creation);
        var course = new CoursePlan("Title", "script", List.of(), 1);
        when(agent.createCourse(eq(1L), any())).thenReturn(course);
        when(store.claimGeneration(1, queue)).thenReturn(Optional.empty());
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.of(snapshot()));
        service.start(1, command);
        var order = inOrder(store, agent);
        order.verify(store).reserve(1, command); order.verify(agent).createCourse(eq(1L), any());
        order.verify(store).prepared(1, creation, course, List.of("script")); order.verify(store).claimGeneration(1, queue);
        verifyNoInteractions(speech);
    }

    @Test void modelFailureRecordsFailedPreparationAndDoesNotDispatchSynthesis() {
        var creation = new ContinuousAudioStore.Creation(queue, "token", true);
        when(store.reserve(eq(1L), any())).thenReturn(creation); when(agent.createCourse(eq(1L), any())).thenThrow(new IllegalStateException("model failed"));
        assertThatThrownBy(() -> service.start(1, command())).isInstanceOf(IllegalStateException.class);
        verify(store).preparationFailed(1, creation, "model failed"); verify(store, never()).claimGeneration(anyLong(), anyString()); verifyNoInteractions(speech);
    }

    @Test void singleLessonUsesDurableReservationAndNeverSplitsItsDraft() {
        String request = UUID.randomUUID().toString();
        var command = new LessonCommand("topic", 2, 3, "beginner", "");
        var queueCommand = new QueueCommand("topic", 2, 3, 3, "beginner", "", 1, request, "SINGLE");
        var creation = new ContinuousAudioStore.Creation(queue, "token", true);
        when(store.reserve(1, queueCommand)).thenReturn(creation, new ContinuousAudioStore.Creation(queue, null, false));
        var draft = new LessonDraft(UUID.randomUUID().toString(), "Single Title", "first paragraph\n\nsecond paragraph", List.of(), 3, 7);
        when(agent.createLesson(1, command)).thenReturn(draft);
        when(store.claimGeneration(1, queue)).thenReturn(Optional.empty());
        var pending = Map.<String, Object>of("queue_id", queue, "version", 2L, "status", "GENERATING", "mode", "SINGLE", "lessons", List.of());
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.of(pending));
        assertThat(service.startSingle(1, command, request)).containsEntry("status", "GENERATING").containsEntry("lessons", List.of());
        assertThat(service.startSingle(1, command, request)).isEqualTo(pending);
        verify(agent, times(1)).createLesson(1, command);
        verify(agent, never()).createCourse(anyLong(), any());
        verify(store).prepared(1, creation, new CoursePlan(draft.title(), draft.script(), draft.citations(), draft.knowledgePointId()), List.of(draft.script()));
        verify(store, times(1)).claimGeneration(1, queue); verifyNoInteractions(speech);
    }

    @Test void failedSinglePreparationDoesNotCallLegacyRecordOrSpeech() {
        var command = new LessonCommand("topic", 2, 3, "beginner", "");
        var creation = new ContinuousAudioStore.Creation(queue, "token", true);
        when(store.reserve(eq(1L), any())).thenReturn(creation);
        when(agent.createLesson(1, command)).thenThrow(new IllegalStateException("single model failed"));
        assertThatThrownBy(() -> service.startSingle(1, command, UUID.randomUUID().toString())).isInstanceOf(IllegalStateException.class);
        verify(store).preparationFailed(1, creation, "single model failed");
        verify(agent, never()).recordLesson(anyLong(), any(), anyString(), anyBoolean()); verifyNoInteractions(speech);
    }

    @Test void workerPreservesUncertainAttemptAndNeverAutomaticallyRepeatsTts() {
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.of(snapshot()));
        when(store.status(1, queue)).thenReturn(snapshot());
        when(store.claimGeneration(1, queue)).thenReturn(Optional.of("token"));
        var claimed = claim("SYNTHESIZING");
        when(store.claimNext(1, queue, "token")).thenReturn(Optional.of(claimed));
        when(speech.synthesize(audio, "script", "")).thenThrow(new IllegalStateException("provider result unknown"));
        service.refill(1, queue);
        verify(store, timeout(2000)).uncertain(1, claimed, "provider result unknown");
        verify(store, timeout(2000)).releaseGeneration(1, queue, "token");
        verify(speech, times(1)).synthesize(audio, "script", ""); verify(speech, never()).remove(anyString());
        verify(store, never()).complete(anyLong(), any(), anyString());
    }

    @Test void workerValidatesWavBeforeCommittingReady() throws Exception {
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.of(snapshot())); when(store.status(1, queue)).thenReturn(snapshot());
        when(store.claimGeneration(1, queue)).thenReturn(Optional.of("token"));
        var claimed = claim("SYNTHESIZING");
        when(store.claimNext(1, queue, "token")).thenReturn(Optional.of(claimed), Optional.empty());
        String url = "/media/" + audio + ".wav"; when(speech.synthesize(audio, "script", "")).thenReturn(url);
        Path file = folder.resolve(audio + ".wav"); Files.write(file, wav()); when(speech.audioFile(audio + ".wav")).thenReturn(file);
        service.refill(1, queue);
        verify(store, timeout(2000)).complete(1, claimed, url); verify(store, timeout(2000)).releaseGeneration(1, queue, "token");
        verify(store, never()).uncertain(anyLong(), any(), anyString());
    }

    @Test void providerSuccessWithInvalidWavStillRequiresReview() throws Exception {
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.of(snapshot())); when(store.status(1, queue)).thenReturn(snapshot());
        when(store.claimGeneration(1, queue)).thenReturn(Optional.of("token"));
        var claimed = claim("SYNTHESIZING"); when(store.claimNext(1, queue, "token")).thenReturn(Optional.of(claimed));
        when(speech.synthesize(audio, "script", "")).thenReturn("/media/" + audio + ".wav");
        Path file = folder.resolve(audio + ".wav"); Files.write(file, new byte[12]); when(speech.audioFile(audio + ".wav")).thenReturn(file);
        service.refill(1, queue);
        verify(store, timeout(2000)).uncertain(eq(1L), eq(claimed), contains("Invalid WAV"));
        verify(store, timeout(2000)).releaseGeneration(1, queue, "token");
        verify(store, never()).complete(anyLong(), any(), anyString()); verify(speech, never()).remove(anyString());
    }

    @Test void saturatedWorkerHandoffRejectsThirdQueueWithoutBacklogOrTts() throws Exception {
        String secondQueue = UUID.randomUUID().toString(); String thirdQueue = UUID.randomUUID().toString();
        String secondAudio = UUID.randomUUID().toString(); String thirdAudio = UUID.randomUUID().toString();
        var secondClaim = new ContinuousAudioStore.Claim(secondQueue, 1, hash, secondAudio, "second", "SYNTHESIZING", command(),
                new LessonDraft(secondAudio, "Title", "script", List.of(), 1, 1));
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        when(store.statusIfPresent(eq(1L), anyString())).thenReturn(Optional.of(snapshot()));
        when(store.status(eq(1L), anyString())).thenReturn(snapshot());
        when(store.claimGeneration(1, queue)).thenReturn(Optional.of("token"));
        when(store.claimGeneration(1, secondQueue)).thenReturn(Optional.of("second"));
        when(store.claimGeneration(1, thirdQueue)).thenReturn(Optional.of("third"));
        when(store.claimNext(1, queue, "token")).thenReturn(Optional.of(claim("SYNTHESIZING")), Optional.empty());
        when(store.claimNext(1, secondQueue, "second")).thenReturn(Optional.of(secondClaim), Optional.empty());
        when(speech.synthesize(anyString(), eq("script"), eq(""))).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return "/media/" + invocation.getArgument(0) + ".wav";
        });
        Files.write(folder.resolve(audio + ".wav"), wav()); Files.write(folder.resolve(secondAudio + ".wav"), wav());
        when(speech.audioFile(anyString())).thenAnswer(invocation -> folder.resolve((String) invocation.getArgument(0)));
        try {
            service.refill(1, queue); service.refill(1, secondQueue);
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.refill(1, thirdQueue)).isInstanceOf(ApiException.class).hasMessageContaining("不会在后台排队");
            verify(store).releaseGeneration(1, thirdQueue, "third");
            verify(store, never()).claimNext(1, thirdQueue, "third");
            verify(speech, never()).synthesize(eq(thirdAudio), anyString(), anyString());
        } finally { release.countDown(); }
        verify(store, timeout(3000)).releaseGeneration(1, queue, "token");
        verify(store, timeout(3000)).releaseGeneration(1, secondQueue, "second");
    }

    @Test void rejectedSubmissionCanBeExplicitlyRefilledWhenWorkerAvailable() throws Exception {
        service.close(); var worker = mock(ExecutorService.class);
        service = new ContinuousAudioService(agent, speech, store, redis, json, worker);
        when(worker.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("busy"))
                .thenAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return CompletableFuture.completedFuture(null); });
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.of(snapshot())); when(store.status(1, queue)).thenReturn(snapshot());
        when(store.claimGeneration(1, queue)).thenReturn(Optional.of("rejected"), Optional.of("accepted"));
        var accepted = new ContinuousAudioStore.Claim(queue, 1, hash, audio, "accepted", "SYNTHESIZING", command(),
                new LessonDraft(audio, "Title", "script", List.of(), 1, 1));
        when(store.claimNext(1, queue, "accepted")).thenReturn(Optional.of(accepted), Optional.empty());
        when(speech.synthesize(audio, "script", "")).thenReturn("/media/" + audio + ".wav");
        Path file = folder.resolve(audio + ".wav"); Files.write(file, wav()); when(speech.audioFile(audio + ".wav")).thenReturn(file);
        assertThatThrownBy(() -> service.refill(1, queue)).isInstanceOf(ApiException.class).hasMessageContaining("已释放");
        verify(store).releaseGeneration(1, queue, "rejected"); verifyNoInteractions(speech);
        assertThat(service.refill(1, queue)).isEqualTo(snapshot());
        verify(speech, times(1)).synthesize(audio, "script", ""); verify(store).complete(1, accepted, "/media/" + audio + ".wav");
    }

    @Test void shutdownWorkerRejectsNewWorkWithoutLeavingAnActiveClaim() {
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.of(snapshot()));
        when(store.claimGeneration(1, queue)).thenReturn(Optional.of("token")); service.close();
        assertThatThrownBy(() -> service.refill(1, queue)).isInstanceOf(ApiException.class).hasMessageContaining("已停止");
        verify(store).releaseGeneration(1, queue, "token"); verifyNoInteractions(speech);
    }

    @Test void durableStatusDoesNotReadPossiblyStaleRedis() {
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.of(snapshot()));
        assertThat(service.status(1, queue)).isEqualTo(snapshot());
        verify(values, never()).get(anyString());
    }

    @Test void failedCacheWriteDoesNotFailCommittedStatus() {
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.of(snapshot()));
        doThrow(new IllegalStateException("redis unavailable")).when(redis).execute(any(DefaultRedisScript.class), anyList(), any(), any());
        assertThat(service.status(1, queue)).isEqualTo(snapshot()); verifyNoInteractions(agent, speech);
    }

    @Test void legacyStatusIsReadOnlyAndRefillCannotReplayIt() {
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.empty()); when(store.ownsProject(1, 2)).thenReturn(true);
        when(values.get("audiorag:continuous:" + queue)).thenReturn("{\"user_id\":1,\"command\":{\"projectId\":2},\"lessons\":[],\"scripts\":[\"old\"]}");
        assertThat(service.status(1, queue)).containsEntry("status", "LEGACY_READ_ONLY");
        assertThatThrownBy(() -> service.refill(1, queue)).isInstanceOf(ApiException.class).hasMessageContaining("仅支持查看");
        verify(store, never()).claimGeneration(anyLong(), anyString()); verifyNoInteractions(agent, speech); verify(redis, never()).delete(anyString());
    }

    @Test void legacyOwnershipIsCheckedAgainstProjectBeforeReturningText() {
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.empty());
        when(values.get("audiorag:continuous:" + queue)).thenReturn("{\"user_id\":1,\"command\":{\"projectId\":2},\"lessons\":[],\"scripts\":[]}");
        assertThatThrownBy(() -> service.status(1, queue)).isInstanceOf(ApiException.class);
        verify(store).ownsProject(1, 2);
    }

    @Test void recoveryChecksBindingsBeforeTouchingFilesystem() {
        when(store.recoveryCandidate(1, queue, 1, true, hash, audio)).thenThrow(new IllegalStateException("hash mismatch"));
        assertThatThrownBy(() -> service.recoverSegment(1, queue, 1, true, hash, audio)).hasMessage("hash mismatch");
        verifyNoInteractions(speech);
    }

    @Test void completeWavCanBeExplicitlyRecoveredWithoutTts() throws Exception {
        when(store.recoveryCandidate(1, queue, 1, true, hash, audio)).thenReturn(claim("NEEDS_REVIEW"));
        Path file = folder.resolve(audio + ".wav"); Files.write(file, wav()); when(speech.audioFile(audio + ".wav")).thenReturn(file);
        when(store.recovered(1, queue, 1, hash, audio)).thenReturn(snapshot());
        assertThat(service.recoverSegment(1, queue, 1, true, hash, audio)).isEqualTo(snapshot());
        verify(store).recovered(1, queue, 1, hash, audio); verify(speech, never()).synthesize(anyString(), anyString(), anyString());
    }

    @Test void truncatedWavCannotBeRecoveredOrWrittenAsReady() throws Exception {
        when(store.recoveryCandidate(1, queue, 1, true, hash, audio)).thenReturn(claim("NEEDS_REVIEW"));
        Path file = folder.resolve(audio + ".wav"); Files.write(file, new byte[12]); when(speech.audioFile(audio + ".wav")).thenReturn(file);
        assertThatThrownBy(() -> service.recoverSegment(1, queue, 1, true, hash, audio)).isInstanceOf(ApiException.class).hasMessageContaining("不完整");
        verify(store, never()).recovered(anyLong(), anyString(), anyInt(), anyString(), anyString()); verify(speech, never()).remove(anyString());
    }

    @Test void alreadyRecoveredAudioDoesNotReadWavAgain() {
        when(store.recoveryCandidate(1, queue, 1, true, hash, audio)).thenReturn(claim("READY"));
        when(store.statusIfPresent(1, queue)).thenReturn(Optional.of(snapshot()));
        assertThat(service.recoverSegment(1, queue, 1, true, hash, audio)).isEqualTo(snapshot()); verifyNoInteractions(speech);
    }

    @Test void resetOnlyUpdatesStateAndNeverDeletesOrResynthesizesAudio() {
        String reason = "人工核对后明确重新预留合成尝试";
        when(store.reset(1, queue, 1, true, hash, audio, reason)).thenReturn(snapshot());
        assertThat(service.resetSegment(1, queue, 1, true, hash, audio, reason)).isEqualTo(snapshot());
        verifyNoInteractions(speech, agent); verify(store, never()).claimGeneration(anyLong(), anyString());
    }

    private QueueCommand command() { return new QueueCommand("topic", 2, 10, 1, "beginner", "", 2, UUID.randomUUID().toString()); }
    private Map<String, Object> snapshot() { return Map.of("queue_id", queue, "version", 2L, "status", "READY", "prefetch_size", 2); }
    private ContinuousAudioStore.Claim claim(String status) { return new ContinuousAudioStore.Claim(queue, 1, hash, audio, "token", status, command(), new LessonDraft(audio, "Title", "script", List.of(), 1, 1)); }
    private byte[] wav() {
        var bytes = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x46464952).putInt(38).putInt(0x45564157).putInt(0x20746d66).putInt(16);
        bytes.putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16);
        bytes.putInt(0x61746164).putInt(2).putShort((short) 0); return bytes.array();
    }
}
