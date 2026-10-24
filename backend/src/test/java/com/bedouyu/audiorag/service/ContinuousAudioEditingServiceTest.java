package com.bedouyu.audiorag.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.service.ContinuousAudioStore.ScriptRevision;
import com.bedouyu.audiorag.service.ContinuousAudioStore.SegmentRevision;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

class ContinuousAudioEditingServiceTest {
    private final ContinuousAudioStore store = mock(ContinuousAudioStore.class);
    private final SpeechService speech = mock(SpeechService.class);
    private final ScriptReviser reviser = mock(ScriptReviser.class);
    private final ContinuousAudioService service = new ContinuousAudioService(mock(AudioAgent.class), speech, store,
            mock(StringRedisTemplate.class), new ObjectMapper(), Executors.newSingleThreadExecutor());
    private final String queue = UUID.randomUUID().toString();
    private static final String ORIGINAL = "这是一段原有中文讲稿，我们一起讲述小狐狸寻找月亮的故事。";
    private static final String EDITED = "这是一段改后的中文讲稿，我们一起讲述小狐狸在湖边寻找月亮的故事。";
    private static final String ENGLISH_LONG = ("The old courtyard wakes in the morning light, and a gentle breeze carries the scent of spring. ")
            .repeat(8).substring(0, 597);
    private static final String CHINESE_SHORT = ("清晨的老院里，春风轻轻掠过青石板，昨夜的一场细雨洗亮了门前的树叶。")
            .repeat(10).substring(0, 264);

    ContinuousAudioEditingServiceTest() { service.setReviser(reviser); }
    @AfterEach void close() { service.close(); }

    @Test void stoppedSingleRevisionDoesNotWriteOrStartSynthesisAfterModelReturns() {
        when(store.scriptRevision(1, queue, 1)).thenReturn(baseline(1));
        when(reviser.revise(ORIGINAL, "改口语")).thenReturn(EDITED);

        assertThatThrownBy(() -> service.reviseSegment(1, queue, 1, "改口语", null, this::stopped))
                .hasMessage("已停止");

        verify(reviser).revise(ORIGINAL, "改口语");
        verify(store, never()).reviseScript(anyLong(), anyString(), anyInt(), anyString(), anyString(), any(), any());
        verify(store, never()).claimGeneration(anyLong(), anyString());
        verifyNoInteractions(speech);
    }

    @Test void stoppedWholeRewriteDoesNotWriteOrStartSynthesisAfterModelReturns() {
        when(store.fullScriptRevision(1, queue)).thenReturn(baseline(null));
        when(reviser.rewriteWhole(ORIGINAL, "改结构")).thenReturn(EDITED);

        assertThatThrownBy(() -> service.rewriteWhole(1, queue, "改结构", this::stopped))
                .hasMessage("已停止");

        verify(reviser).rewriteWhole(ORIGINAL, "改结构");
        verify(store, never()).replaceScripts(anyLong(), anyString(), anyList(), any(), any());
        verify(store, never()).claimGeneration(anyLong(), anyString());
        verifyNoInteractions(speech);
    }

    @Test void stoppedExtensionDoesNotWriteOrStartSynthesisAfterModelReturns() {
        when(store.fullScriptRevision(1, queue)).thenReturn(baseline(null));
        when(reviser.extend(ORIGINAL, "继续故事")).thenReturn(EDITED);

        assertThatThrownBy(() -> service.extendWhole(1, queue, "继续故事", this::stopped))
                .hasMessage("已停止");

        verify(reviser).extend(ORIGINAL, "继续故事");
        verify(store, never()).appendScripts(anyLong(), anyString(), anyList(), any(), any());
        verify(store, never()).claimGeneration(anyLong(), anyString());
        verifyNoInteractions(speech);
    }

    @Test void revisingLaterSegmentStagesChangesWithoutChangingTheLiveScriptOrSynthesizing() {
        var baseline = baseline(3);
        when(store.scriptRevision(1, queue, 3)).thenReturn(baseline);
        when(store.proposeScriptRevision(eq(1L), eq(queue), eq("REVISE"), eq(3), eq(List.of(EDITED)), anyString(), eq(baseline), any()))
                .thenReturn(Map.of("script_revision", Map.of("status", "PENDING")));

        service.reviseSegment(1, queue, 3, null, EDITED);

        verify(store).proposeScriptRevision(eq(1L), eq(queue), eq("REVISE"), eq(3), eq(List.of(EDITED)), anyString(), eq(baseline), any());
        verify(store, never()).reviseScript(anyLong(), anyString(), anyInt(), anyString(), anyString(), any(), any());
        verify(store, never()).claimGeneration(anyLong(), anyString());
        verifyNoInteractions(speech);
    }

    @Test void extensionStagesNewContentWithoutAppendingOrSynthesizing() {
        var baseline = baseline(null);
        when(store.fullScriptRevision(1, queue)).thenReturn(baseline);
        when(reviser.extend(ORIGINAL, "继续故事")).thenReturn(EDITED);

        service.extendWhole(1, queue, "继续故事");

        verify(store).proposeScriptRevision(eq(1L), eq(queue), eq("EXTEND"), isNull(), anyList(), eq("继续故事"), eq(baseline), any(), isNull());
        verify(store, never()).appendScripts(anyLong(), anyString(), anyList(), any(), any());
        verify(store, never()).claimGeneration(anyLong(), anyString());
        verifyNoInteractions(speech);
    }

    @Test void savingUnchangedTextDoesNotRetireAudioOrResynthesize() {
        when(store.scriptRevision(1, queue, 1)).thenReturn(baseline(1));
        when(store.status(1, queue)).thenReturn(Map.of());

        service.reviseSegment(1, queue, 1, null, ORIGINAL);

        verify(store, never()).reviseScript(anyLong(), anyString(), anyInt(), anyString(), anyString(), any(), any());
        verify(store, never()).claimGeneration(anyLong(), anyString());
        verifyNoInteractions(reviser, speech);
    }

    @Test void aBrowserEditBasedOnAnOlderScriptCannotOverwriteTheCurrentText() {
        when(store.scriptRevision(1, queue, 1)).thenReturn(baseline(1));
        assertThatThrownBy(() -> service.reviseSegment(1, queue, 1, null, EDITED,
                com.bedouyu.audiorag.common.Hashing.sha256("较早的浏览器正文"), () -> { }))
                .isInstanceOfSatisfying(com.bedouyu.audiorag.common.ApiException.class,
                        error -> assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
        verify(store, never()).reviseScript(anyLong(), anyString(), anyInt(), anyString(), anyString(), any(), any());
        verifyNoInteractions(reviser, speech);
    }

    @Test void acceptingAProposalDispatchesOnlyTheFirstAffectedSegment() {
        String id = UUID.randomUUID().toString();
        when(store.scriptRevisionForReview(1, queue, id)).thenReturn(Map.of("status", "PENDING", "after_text", EDITED));
        when(store.commandForUser(1, queue)).thenReturn(command("clone-test"));
        when(store.decideScriptRevision(1, queue, id, true, true)).thenReturn(new ContinuousAudioStore.RevisionDecision(
                Map.of("script_revision", Map.of("status", "ACCEPTED")), true, 4, List.of(), false));
        when(store.claimGeneration(1, queue)).thenReturn(Optional.of("run"));
        when(store.claimNext(1, queue, "run", 4)).thenReturn(Optional.empty());
        when(store.status(1, queue)).thenReturn(Map.of());
        service.acceptScriptRevision(1, queue, id, true);
        verify(speech).selectVoice("clone-test", "zh");
        verify(store, timeout(2000)).claimNext(1, queue, "run", 4);
        verify(store, never()).claimNext(anyLong(), anyString(), anyString());
        verify(speech, never()).synthesize(anyString(), anyString(), any());
    }

    @Test void replayingAcceptanceCannotDispatchSynthesisAgain() {
        String id = UUID.randomUUID().toString();
        when(store.scriptRevisionForReview(1, queue, id)).thenReturn(Map.of("status", "ACCEPTED", "after_text", EDITED));
        when(store.decideScriptRevision(1, queue, id, true, true)).thenReturn(new ContinuousAudioStore.RevisionDecision(
                Map.of("script_revision", Map.of("status", "ACCEPTED")), false, 1, List.of(), false));
        service.acceptScriptRevision(1, queue, id, true);
        verify(store, never()).claimGeneration(anyLong(), anyString());
        verifyNoInteractions(speech);
    }

    @Test void explicitLanguageChangeCanStageAShorterSegmentWithoutApplyingOrSynthesizing() {
        String instruction = "把这段改为中文的中国散文";
        var baseline = baseline(1, ENGLISH_LONG);
        when(store.scriptRevision(1, queue, 1)).thenReturn(baseline);
        when(reviser.revise(ENGLISH_LONG, instruction)).thenReturn(CHINESE_SHORT);

        service.reviseSegment(1, queue, 1, instruction, null);

        assertThat(ENGLISH_LONG).hasSize(597);
        assertThat(CHINESE_SHORT).hasSize(264);
        verify(store).proposeScriptRevision(eq(1L), eq(queue), eq("REVISE"), eq(1), eq(List.of(CHINESE_SHORT)),
                eq(instruction), eq(baseline), any());
        verify(store, never()).reviseScript(anyLong(), anyString(), anyInt(), anyString(), anyString(), any(), any());
        verify(store, never()).claimGeneration(anyLong(), anyString());
        verifyNoInteractions(speech);
    }

    @Test void accidentalLanguageChangeStillCannotBecomeASegmentProposal() {
        when(store.scriptRevision(1, queue, 1)).thenReturn(baseline(1, ENGLISH_LONG));
        when(reviser.revise(ENGLISH_LONG, "把当前讲稿写得更生动")).thenReturn(CHINESE_SHORT);

        assertThatThrownBy(() -> service.reviseSegment(1, queue, 1, "把当前讲稿写得更生动", null))
                .isInstanceOf(com.bedouyu.audiorag.common.ApiException.class);

        verify(store, never()).proposeScriptRevision(anyLong(), anyString(), anyString(), any(), anyList(), anyString(), any(), any());
        verifyNoInteractions(speech);
    }

    @Test void accidentalLanguageChangeStillCannotBecomeAWholeScriptProposal() {
        when(store.fullScriptRevision(1, queue)).thenReturn(baseline(null, ENGLISH_LONG));
        when(reviser.rewriteWhole(ENGLISH_LONG, "把当前讲稿写得更生动")).thenReturn(CHINESE_SHORT);

        assertThatThrownBy(() -> service.rewriteWhole(1, queue, "把当前讲稿写得更生动"))
                .isInstanceOf(com.bedouyu.audiorag.common.ApiException.class);

        verify(store, never()).proposeScriptRevision(anyLong(), anyString(), anyString(), any(), anyList(), anyString(), any(), any(), any());
        verifyNoInteractions(speech);
    }

    @Test void aManualLanguageChangeIsReviewableWithoutCallingTheModel() {
        var baseline = baseline(1, ENGLISH_LONG);
        when(store.scriptRevision(1, queue, 1)).thenReturn(baseline);

        service.reviseSegment(1, queue, 1, null, CHINESE_SHORT);

        verify(store).proposeScriptRevision(eq(1L), eq(queue), eq("REVISE"), eq(1), eq(List.of(CHINESE_SHORT)),
                eq("手工编辑"), eq(baseline), any());
        verify(store, never()).claimGeneration(anyLong(), anyString());
        verifyNoInteractions(reviser, speech);
    }

    @Test void unsupportedVoiceIsRejectedBeforeAcceptanceCanChangeTheLiveScript() {
        String id = UUID.randomUUID().toString();
        when(store.scriptRevisionForReview(1, queue, id)).thenReturn(Map.of("status", "PENDING", "after_text", CHINESE_SHORT));
        when(store.commandForUser(1, queue)).thenReturn(command("english-only"));
        when(speech.selectVoice("english-only", "zh")).thenThrow(new com.bedouyu.audiorag.common.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "所选音色不支持请求的语言：zh"));

        assertThatThrownBy(() -> service.acceptScriptRevision(1, queue, id, true))
                .hasMessageContaining("不支持请求的语言");

        verify(store, never()).decideScriptRevision(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean());
        verify(store, never()).claimGeneration(anyLong(), anyString());
        verify(speech, never()).synthesize(anyString(), anyString(), any());
    }

    @Test void replayingAnInstructionReturnsItsStoredProposalWithoutCallingTheModelAgain() {
        var stored = Map.<String, Object>of("script_revision", Map.of("status", "REJECTED"));
        when(store.existingScriptRevision(1, queue, "request")).thenReturn(Optional.of(stored));
        assertThat(service.proposeInstruction(1, queue, "继续故事", "EXTEND", "request")).isSameAs(stored);
        verifyNoInteractions(reviser, speech);
    }

    private ScriptRevision baseline(Integer number) {
        return baseline(number, ORIGINAL);
    }
    private ScriptRevision baseline(Integer number, String script) {
        return new ScriptRevision(queue, number, script,
                List.of(new SegmentRevision(1L, number == null ? 1 : number, 2L, "hash", "audio")));
    }
    private ContinuousAudioService.QueueCommand command(String voice) {
        return new ContinuousAudioService.QueueCommand("测试讲稿", 1, 3, 1, null, voice, 1,
                UUID.randomUUID().toString(), "CONTINUOUS");
    }
    private void stopped() { throw new IllegalStateException("已停止"); }
}
