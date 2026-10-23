package com.bedouyu.audiorag.controller;

import com.bedouyu.audiorag.model.dto.*;

import com.bedouyu.audiorag.common.ApiException;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.agent.audio.AudioAgent.ChatCommand;
import com.bedouyu.audiorag.agent.audio.AudioAgent.LessonCommand;
import com.bedouyu.audiorag.agent.audio.AudioAgent.LessonDraft;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.service.ContinuousAudioService;
import com.bedouyu.audiorag.service.ContinuousAudioService.QueueCommand;
import com.bedouyu.audiorag.service.AuthService;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.KnowledgeService;
import com.bedouyu.audiorag.service.KnowledgeService.Download;
import com.bedouyu.audiorag.tools.audio.TranscriptionService;
import com.bedouyu.audiorag.service.LearningPlanService;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class AiController {
    private final AuthService auth;
    private final AudioAgent agent;
    private final SpeechService speech;
    private final AudioRagRepository repository;
    private final ContinuousAudioService continuousAudio;
    private final KnowledgeService knowledge;
    private final TranscriptionService transcription;
    private final LearningPlanService learningPlan;

    public AiController(AuthService auth, AudioAgent agent, SpeechService speech, AudioRagRepository repository,
            ContinuousAudioService continuousAudio, KnowledgeService knowledge,
            TranscriptionService transcription, LearningPlanService learningPlan) {
        this.auth = auth;
        this.agent = agent;
        this.speech = speech;
        this.repository = repository;
        this.continuousAudio = continuousAudio;
        this.knowledge = knowledge;
        this.transcription = transcription;
        this.learningPlan = learningPlan;
    }

    @GetMapping({"/health", "/api/health"})
    public Map<String, Object> health() {
        boolean ragReady = agent.ready();
        boolean minioReady = knowledge.ready();
        boolean redisReady = continuousAudio.ready();
        boolean audioReady = continuousAudio.durableReady();
        return Map.of("status", ragReady && minioReady && audioReady ? "ok" : "degraded", "backend", "spring-boot",
                "rag", ragReady ? "ok" : "unavailable", "minio", minioReady ? "ok" : "unavailable",
                "redis", redisReady ? "ok" : "unavailable",
                "continuous_audio", audioReady ? "mysql-ready" : "unavailable",
                "transcription", transcription.configured() ? "configured" : "manual-text",
                "generation", agent.capabilities(), "python_worker", "disabled");
    }

    @PostMapping("/api/v1/chat")
    public Map<String, Object> chat(@RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ChatRequest request) {
        return agent.chat(userId(authorization),
                new ChatCommand(request.message(), request.sessionId(), request.projectId(), request.memoryStrategy(), request.requestId(), request.requiredArtifacts()));
    }

    @GetMapping("/api/v1/sessions/{sessionId}/trace")
    public Map<String, Object> trace(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionId) {
        repository.requireSessionOwner(userId(authorization), sessionId);
        return Map.of("session_id", sessionId, "events", repository.events(sessionId));
    }

    @PostMapping("/api/v1/lessons")
    public ResponseEntity<Map<String, Object>> lesson(@RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody LessonRequest request) {
        var result = continuousAudio.startSingle(userId(authorization), new LessonCommand(request.topic(),
                request.projectId(), request.durationMinutes(), request.audience(), request.voice()), request.requestId());
        // 接收任务不等于已生成音频；音频地址只从确认READY的片段读取。
        boolean terminal = java.util.Set.of("COMPLETE", "FAILED", "NEEDS_REVIEW", "CANCELLED").contains(result.get("status"));
        return ResponseEntity.status(terminal ? 200 : 202).body(result);
    }

    @PostMapping("/api/v1/continuous-audio")
    public Map<String, Object> startContinuous(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ContinuousRequest request) {
        long userId = userId(authorization);
        if (!repository.ownsProject(userId, request.projectId())) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "项目不存在或无访问权限");
        }
        String topic = repository.projectTopic(userId, request.projectId());
        return continuousAudio.start(userId, new QueueCommand(topic, request.projectId(),
                request.courseMinutes(), request.segmentMinutes(), request.audience(), request.voice(), request.prefetchSize(), request.requestId()));
    }

    @GetMapping("/api/v1/continuous-audio/{queueId}")
    public Map<String, Object> continuousStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String queueId) {
        return continuousAudio.status(userId(authorization), queueId);
    }

    /** 各段正文合并成一份，供前端查看与导出为单个文件。 */
    @GetMapping("/api/v1/continuous-audio/{queueId}/script")
    public Map<String, Object> continuousScript(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String queueId) {
        long user = userId(authorization);
        var status = continuousAudio.status(user, queueId);
        String script = continuousAudio.script(user, queueId);
        return Map.of("queue_id", queueId,
                "title", String.valueOf(status.getOrDefault("title", "")),
                "mode", String.valueOf(status.getOrDefault("mode", "")),
                "segments", ((Number) status.getOrDefault("total_segments", 0)).intValue(),
                "characters", script.length(),
                "script", script);
    }

    /** 换音色重念：只重跑语音合成，讲稿不变；旧音频保留一段时间后自动清理。 */
    @PostMapping("/api/v1/continuous-audio/{queueId}/revoice")
    public Map<String, Object> revoice(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String queueId, @RequestBody(required = false) Map<String, Object> body) {
        Object voice = body == null ? null : body.get("voice");
        return continuousAudio.revoice(userId(authorization), queueId, voice == null ? null : String.valueOf(voice));
    }

    /** 追加：在现有讲稿后继续写新内容，原有段落不动。 */
    @PostMapping("/api/v1/continuous-audio/{queueId}/extend")
    public Map<String, Object> extendScript(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String queueId, @RequestBody(required = false) Map<String, Object> body) {
        Object instruction = body == null ? null : body.get("instruction");
        return continuousAudio.extendWhole(userId(authorization), queueId,
                instruction == null ? null : String.valueOf(instruction));
    }
    /** 整篇重写讲稿：改格式或结构时用，结果直接写回这条音频的讲稿。 */
    @PostMapping("/api/v1/continuous-audio/{queueId}/rewrite")
    public Map<String, Object> rewriteScript(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String queueId, @RequestBody(required = false) Map<String, Object> body) {
        Object instruction = body == null ? null : body.get("instruction");
        return continuousAudio.rewriteWhole(userId(authorization), queueId,
                instruction == null ? null : String.valueOf(instruction));
    }
    /** 在当前讲稿基础上改某一段：给 instruction 由模型改写，或给 text 直接手工修改。 */
    @PostMapping("/api/v1/continuous-audio/{queueId}/segments/{number}/revise")
    public Map<String, Object> reviseSegment(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String queueId, @PathVariable int number,
            @RequestBody(required = false) Map<String, Object> body) {
        Object instruction = body == null ? null : body.get("instruction");
        Object text = body == null ? null : body.get("text");
        return continuousAudio.reviseSegment(userId(authorization), queueId, number,
                instruction == null ? null : String.valueOf(instruction),
                text == null ? null : String.valueOf(text));
    }

    /** 撤回上一版讲稿：改回上一次修订前的文字并重新合成这一段。 */
    @PostMapping("/api/v1/continuous-audio/{queueId}/segments/{number}/undo")
    public Map<String, Object> undoRevision(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String queueId, @PathVariable int number) {
        return continuousAudio.undoRevision(userId(authorization), queueId, number);
    }

    @PostMapping("/api/v1/continuous-audio/{queueId}/refill")
    public Map<String, Object> refill(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String queueId, @RequestBody(required = false) Map<String, Object> body) {
        Object requested = body == null ? null : body.get("segments");
        int segments = requested instanceof Number number ? number.intValue() : 0;
        return continuousAudio.refill(userId(authorization), queueId, segments);
    }

    @PostMapping("/api/v1/continuous-audio/{queueId}/segments/{segmentNumber}/recover")
    public Map<String, Object> recoverAudioSegment(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String queueId, @PathVariable int segmentNumber, @RequestBody SegmentConfirmation request) {
        return continuousAudio.recoverSegment(userId(authorization), queueId, segmentNumber, request.confirmed(),
                request.expectedScriptHash(), request.expectedAudioId());
    }

    @PostMapping("/api/v1/continuous-audio/{queueId}/segments/{segmentNumber}/reset")
    public Map<String, Object> resetAudioSegment(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String queueId, @PathVariable int segmentNumber, @RequestBody SegmentConfirmation request) {
        return continuousAudio.resetSegment(userId(authorization), queueId, segmentNumber, request.confirmed(),
                request.expectedScriptHash(), request.expectedAudioId(), request.reason());
    }
    public record SegmentConfirmation(boolean confirmed,
            @JsonProperty("expected_script_hash") String expectedScriptHash,
            @JsonProperty("expected_audio_id") String expectedAudioId, String reason) { }

    @PostMapping(value = "/api/v1/knowledge/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam MultipartFile file, @RequestParam String title,
            @RequestParam(name = "project_id") long projectId,
            @RequestParam(required = false) String transcript) throws IOException {
        return knowledge.ingest(userId(authorization), projectId, title,
                file.getOriginalFilename() == null ? "document.bin" : file.getOriginalFilename(),
                file.getContentType(), file.getBytes(), transcript);
    }

    @GetMapping("/api/v1/projects/{projectId}/knowledge")
    public Map<String, Object> knowledgeList(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long projectId) {
        return Map.of("documents", knowledge.list(userId(authorization), projectId));
    }

    @GetMapping("/api/v1/projects/{projectId}/learning-plan")
    public Map<String, Object> learningPlan(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long projectId) {
        long userId = userId(authorization);
        if (!repository.ownsProject(userId, projectId)) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "项目不存在或无访问权限");
        }
        return Map.of("points", learningPlan.list(projectId));
    }

    @GetMapping("/api/v1/projects/{projectId}/knowledge/{recordId}/download")
    public ResponseEntity<Resource> downloadKnowledge(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long projectId, @PathVariable long recordId) {
        Download download = knowledge.download(userId(authorization), projectId, recordId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.filename(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(download.stream()));
    }

    @DeleteMapping("/api/v1/projects/{projectId}/knowledge/{recordId}")
    public Map<String, Object> deleteKnowledge(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long projectId, @PathVariable long recordId) {
        knowledge.delete(userId(authorization), projectId, recordId);
        return Map.of("deleted", true);
    }

    @GetMapping("/media/{filename:.+}")
    public ResponseEntity<Resource> media(@PathVariable String filename) {
        Path path = speech.audioFile(filename);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.parseMediaType("audio/wav"))
                .contentLength(path.toFile().length())
                .body(new FileSystemResource(path));
    }

    private long userId(String authorization) {
        return ((Number) auth.requireUser(authorization).get("id")).longValue();
    }

}
