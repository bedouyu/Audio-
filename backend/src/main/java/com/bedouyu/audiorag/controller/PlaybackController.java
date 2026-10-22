package com.bedouyu.audiorag.controller;

import com.bedouyu.audiorag.service.AuthService;
import com.bedouyu.audiorag.service.PlaybackProgressService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audio/queues/{queueId}/progress")
public class PlaybackController {
    private final AuthService auth;
    private final PlaybackProgressService progress;
    public PlaybackController(AuthService auth, PlaybackProgressService progress) { this.auth = auth; this.progress = progress; }
    @GetMapping public Map<String, Object> get(@RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String queueId) { return progress.get(user(token), queueId); }
    @PutMapping public Map<String, Object> save(@RequestHeader(value = "Authorization", required = false) String token,
            @PathVariable String queueId, @RequestBody Position position) {
        return progress.save(user(token), queueId, position.segmentIndex(), position.positionSeconds(), position.playbackRate(), position.completed());
    }
    private long user(String token) { return ((Number) auth.requireUser(token).get("id")).longValue(); }
    public record Position(@JsonProperty("segment_index") int segmentIndex,
            @JsonProperty("position_seconds") double positionSeconds, @JsonProperty("playback_rate") double playbackRate,
            boolean completed) { }
}
