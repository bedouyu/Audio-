package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.tools.audio.WavVerifier;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.core.guard.AgentOutputValidator;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import java.util.Map;
import java.io.IOException;

@Service
public class AgentArtifactApprovalService {
    private final AgentArtifactStore store;
    private final SpeechService speech;
    private final AgentOutputValidator validator;
    public AgentArtifactApprovalService(AgentArtifactStore store, SpeechService speech, AgentOutputValidator validator) {
        this.store = store; this.speech = speech; this.validator = validator;
    }
    public Map<String, Object> synthesize(long user, long project, String id, boolean confirmed, String voice) {
        return synthesize(user, project, id, confirmed, voice, null, null, false, null);
    }
    public Map<String, Object> synthesize(long user, long project, String id, boolean confirmed, String voice,
                                          String expectedContentHash, String reviewId,
                                          boolean overrideReview, String overrideReason) {
        requireConfirmed(confirmed);
        Map<String, Object> artifact = store.beginSynthesis(user, project, id,
                expectedContentHash, reviewId, overrideReview, overrideReason);
        if ("READY".equals(artifact.get("status"))) return artifact;
        try {
            String text = String.valueOf(artifact.get("content")).replaceAll("\\[E[0-9]+\\]", "");
            String url = speech.synthesize(String.valueOf(artifact.get("audio_id")), text, voice);
            return store.completeSynthesis(user, project, id, url);
        } catch (RuntimeException exception) {
            try { store.failSynthesis(user, project, id, String.valueOf(artifact.get("audio_id"))); }
            catch (RuntimeException bookkeeping) { exception.addSuppressed(bookkeeping); }
            throw exception;
        }
    }
    public Map<String, Object> applyPlan(long user, long project, String id, boolean confirmed) {
        return applyPlan(user, project, id, confirmed, null);
    }
    public Map<String, Object> applyPlan(long user, long project, String id, boolean confirmed, String expectedContentHash) {
        requireConfirmed(confirmed);
        var artifact = store.get(user, project, id);
        if (!"LEARNING_PLAN".equals(artifact.get("kind"))) throw new ApiException(HttpStatus.BAD_REQUEST, "此产物不是学习计划");
        validator.validatePlan(String.valueOf(artifact.get("content")));
        return store.applyPlan(user, project, id, expectedContentHash);
    }
    public Map<String, Object> recoverSynthesis(long user, long project, String id, boolean confirmed,
                                                String expectedContentHash, String expectedAudioId, String existingUrl) {
        requireConfirmed(confirmed);
        var artifact = store.get(user, project, id);
        if (!"SCRIPT_DRAFT".equals(artifact.get("kind")))
            throw new ApiException(HttpStatus.BAD_REQUEST, "只有课程讲稿可恢复音频");
        if (expectedContentHash == null || !Hashing.sha256(String.valueOf(artifact.get("content"))).equals(expectedContentHash))
            throw new ApiException(HttpStatus.CONFLICT, "恢复确认必须绑定当前讲稿的 content_hash");
        if (expectedAudioId == null || !expectedAudioId.equals(artifact.get("audio_id"))
                || !("/media/" + expectedAudioId + ".wav").equals(existingUrl))
            throw new ApiException(HttpStatus.CONFLICT, "恢复音频必须绑定原合成编号和地址");
        if ("READY".equals(artifact.get("status")))
            return store.recoverSynthesis(user, project, id, expectedContentHash, expectedAudioId, existingUrl);
        if (!"NEEDS_REVIEW".equals(artifact.get("status")))
            throw new ApiException(HttpStatus.CONFLICT, "只有待人工复核的中断合成可恢复");
        // Validate only the exact retained WAV. Recovery neither reruns TTS nor removes other files.
        try {
            WavVerifier.validate(speech.audioFile(expectedAudioId + ".wav"));
        } catch (IOException invalid) {
            throw new ApiException(HttpStatus.CONFLICT, "原合成音频不完整或不是有效 WAV，请人工检查或明确重置尝试");
        } catch (ApiException unavailable) {
            if (unavailable.status() != HttpStatus.NOT_FOUND) throw unavailable;
            throw new ApiException(HttpStatus.CONFLICT, "原合成音频不存在，请人工检查或明确重置尝试");
        }
        return store.recoverSynthesis(user, project, id, expectedContentHash, expectedAudioId, existingUrl);
    }
    public Map<String, Object> retrySynthesis(long user, long project, String id, boolean confirmed,
                                              String expectedContentHash, String expectedAudioId, String reason) {
        requireConfirmed(confirmed);
        return store.resetSynthesisAttempt(user, project, id, expectedContentHash, expectedAudioId, reason);
    }
    private void requireConfirmed(boolean confirmed) {
        if (!confirmed) throw new ApiException(HttpStatus.BAD_REQUEST, "请先在前端审核产物并明确确认");
    }
}
