package com.bedouyu.audiorag.controller;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.agent.audio.AudioAgent.ChatCommand;
import com.bedouyu.audiorag.service.AuthService;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
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
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    public AgentStreamController(AudioAgent agent, AuthService auth) { this.agent = agent; this.auth = auth; }

    @PostMapping(value = "/api/v1/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestHeader(value = "Authorization", required = false) String header,
                           @Valid @RequestBody com.bedouyu.audiorag.model.dto.ChatRequest request) {
        long user = ((Number) auth.requireUser(header).get("id")).longValue();
        SseEmitter emitter = new SseEmitter(900_000L);
        Future<?> task = workers.submit(() -> {
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
