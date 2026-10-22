package com.bedouyu.audiorag.controller;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.agent.audio.AudioAgent.ChatCommand;
import com.bedouyu.audiorag.service.AuthService;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

@RestController
public class AgentStreamController {
    private final AudioAgent agent;
    private final AuthService auth;
    /** 有界线程池：排队上限达到后立即返回"并发已满"，不再无界堆积阻塞的模型调用。 */
    private final ThreadPoolExecutor workers;
    private final long streamTimeoutMs;

    public AgentStreamController(AudioAgent agent, AuthService auth,
                                 @Value("${audiorag.stream.workers:4}") int workerCount,
                                 @Value("${audiorag.stream.queue-capacity:24}") int queueCapacity,
                                 @Value("${audiorag.stream.timeout-ms:900000}") long streamTimeoutMs) {
        this.agent = agent;
        this.auth = auth;
        this.streamTimeoutMs = streamTimeoutMs;
        this.workers = new ThreadPoolExecutor(workerCount, workerCount, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ThreadPoolExecutor.AbortPolicy());
        this.workers.allowCoreThreadTimeOut(true);
    }

    @PostMapping(value = "/api/v1/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestHeader(value = "Authorization", required = false) String header,
                           @Valid @RequestBody com.bedouyu.audiorag.model.dto.ChatRequest request) {
        long user = ((Number) auth.requireUser(header).get("id")).longValue();
        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        Future<?> task;
        try {
            task = workers.submit(() -> {
                try {
                    send(emitter, "STATUS", "正在检索当前项目资料…");
                    var result = agent.chat(user, new ChatCommand(request.message(), request.sessionId(), request.projectId(), request.memoryStrategy(), request.requestId(), request.requiredArtifacts()),
                            token -> send(emitter, "TOKEN", token), event -> send(emitter, "TOOL", event));
                    send(emitter, "RESULT", result);
                    send(emitter, "DONE", "");
                    emitter.complete();
                } catch (RuntimeException exception) {
                    try { send(emitter, "ERROR", exception.getMessage() == null ? "任务执行失败" : exception.getMessage()); }
                    catch (RuntimeException ignored) { }
                    emitter.complete();
                }
            });
        } catch (RejectedExecutionException rejected) {
            // 队列已满立即明确失败，让客户端稍后重试，而不是无限排队等到超时。
            try { send(emitter, "ERROR", "当前并发对话已满，请等现有任务结束后重试"); }
            catch (RuntimeException ignored) { }
            emitter.complete();
            return emitter;
        }
        emitter.onTimeout(() -> task.cancel(true));
        emitter.onError(ignored -> task.cancel(true));
        return emitter;
    }
    private void send(SseEmitter emitter, String type, Object data) {
        try { emitter.send(SseEmitter.event().data(Map.of("type", type, "data", data))); }
        catch (IOException exception) { throw new IllegalStateException("客户端连接已断开", exception); }
    }
    @PreDestroy void close() { workers.shutdownNow(); }
}
