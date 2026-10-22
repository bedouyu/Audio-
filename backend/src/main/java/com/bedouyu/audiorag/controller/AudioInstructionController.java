package com.bedouyu.audiorag.controller;

import com.bedouyu.audiorag.model.dto.AudioInstructionRequest;
import com.bedouyu.audiorag.service.*;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/audio")
public class AudioInstructionController {
    private final AuthService auth;
    private final NaturalAudioRequestStore requests;
    private final ContinuousAudioService audio;
    private final ContinuousAudioStore queues;
    private final SpeechService speech;
    public AudioInstructionController(AuthService auth, NaturalAudioRequestStore requests, ContinuousAudioService audio,
            ContinuousAudioStore queues, SpeechService speech) {
        this.auth = auth; this.requests = requests; this.audio = audio; this.queues = queues; this.speech = speech;
    }
    @PostMapping("/requests") public ResponseEntity<Map<String, Object>> submit(
            @RequestHeader(value = "Authorization", required = false) String token,
            @Valid @RequestBody AudioInstructionRequest request) {
        long user = user(token);
        var accepted = requests.reserve(user, request);
        var result = new LinkedHashMap<>(audio.startInstruction(user, accepted.command(), accepted.creation()));
        result.put("project", accepted.project()); result.put("action", accepted.resume() ? "RESUME" : "GENERATE");
        result.put("request_id", request.requestId());
        boolean terminal = Set.of("COMPLETE", "FAILED", "NEEDS_REVIEW", "CANCELLED").contains(result.get("status"));
        return ResponseEntity.status(terminal ? 200 : 202).body(result);
    }
    @GetMapping("/history") public Map<String, Object> history(@RequestHeader(value = "Authorization", required = false) String token,
            @RequestParam(name = "project_id", required = false) Long project) {
        return Map.of("queues", queues.history(user(token), project));
    }
    @GetMapping("/voices") public Map<String, Object> voices(@RequestHeader(value = "Authorization", required = false) String token) {
        user(token);
        List<Map<String, Object>> available = speech.voices().stream().map(voice -> {
            var value = new LinkedHashMap<String, Object>(voice);
            value.put("language", voice.getOrDefault("locale", ""));
            value.put("display_name", voice.getOrDefault("display_name", voice.getOrDefault("name", "")));
            return (Map<String, Object>) value;
        }).toList();
        return Map.of("voices", available, "default_voice", "");
    }
    private long user(String token) { return ((Number) auth.requireUser(token).get("id")).longValue(); }
}
