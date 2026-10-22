package com.bedouyu.audiorag.controller;

import com.bedouyu.audiorag.service.AuthService;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/sessions")
public class ConversationController {
    private final AuthService auth;
    private final AudioRagRepository repository;
    public ConversationController(AuthService auth, AudioRagRepository repository) {
        this.auth = auth; this.repository = repository;
    }
    @GetMapping
    public Map<String, Object> list(@RequestHeader(value = "Authorization", required = false) String header,
                                    @PathVariable long projectId) {
        return Map.of("sessions", repository.sessions(user(header), projectId));
    }
    @PostMapping
    public Map<String, String> create(@RequestHeader(value = "Authorization", required = false) String header,
                                     @PathVariable long projectId) {
        return Map.of("session_id", repository.openSession(user(header), projectId, null));
    }
    @GetMapping("/{sessionId}/messages")
    public Map<String, Object> messages(@RequestHeader(value = "Authorization", required = false) String header,
                                        @PathVariable long projectId, @PathVariable String sessionId) {
        return Map.of("messages", repository.sessionMessages(user(header), projectId, sessionId));
    }
    private long user(String header) { return ((Number) auth.requireUser(header).get("id")).longValue(); }
}
