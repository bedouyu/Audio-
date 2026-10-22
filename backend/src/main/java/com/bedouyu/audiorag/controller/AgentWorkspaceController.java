package com.bedouyu.audiorag.controller;

import com.bedouyu.audiorag.agent.AgentRegistry;
import com.bedouyu.audiorag.service.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent")
public class AgentWorkspaceController {
    private final AuthService auth;
    private final AgentRegistry agents;
    private final AgentRunStore runs;
    private final AgentArtifactStore artifacts;
    private final AgentArtifactApprovalService approval;
    public AgentWorkspaceController(AuthService auth, AgentRegistry agents, AgentRunStore runs,
                                     AgentArtifactStore artifacts, AgentArtifactApprovalService approval) {
        this.auth = auth; this.agents = agents; this.runs = runs; this.artifacts = artifacts; this.approval = approval;
    }
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities(@RequestHeader(value = "Authorization", required = false) String header,
                                            @PathVariable long projectId) {
        runs.list(user(header), projectId);
        return Map.of("agents", agents.profiles().stream().map(a -> Map.of(
                "id", a.id(), "title", a.title(), "tools", a.tools(), "artifact_kind", a.artifactKind(), "max_steps", a.maxSteps())).toList());
    }
    @GetMapping("/runs")
    public Map<String, Object> runs(@RequestHeader(value = "Authorization", required = false) String header,
                                    @PathVariable long projectId) {
        return Map.of("runs", runs.list(user(header), projectId));
    }
    @GetMapping("/runs/{runId}")
    public Map<String, Object> run(@RequestHeader(value = "Authorization", required = false) String header,
                                   @PathVariable long projectId, @PathVariable String runId) {
        return runs.get(user(header), projectId, runId);
    }
    @GetMapping("/artifacts")
    public Map<String, Object> artifacts(@RequestHeader(value = "Authorization", required = false) String header,
                                         @PathVariable long projectId) {
        return Map.of("artifacts", artifacts.list(user(header), projectId));
    }
    @GetMapping("/artifacts/{artifactId}")
    public Map<String, Object> artifact(@RequestHeader(value = "Authorization", required = false) String header,
                                        @PathVariable long projectId, @PathVariable String artifactId) {
        return artifacts.get(user(header), projectId, artifactId);
    }
    @PostMapping("/artifacts/{artifactId}/synthesize")
    public Map<String, Object> synthesize(@RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable long projectId, @PathVariable String artifactId, @RequestBody ApprovalRequest request) {
        return approval.synthesize(user(header), projectId, artifactId, request.confirmed(), request.voice(),
                request.expectedContentHash(), request.reviewId(), Boolean.TRUE.equals(request.overrideReview()), request.overrideReason());
    }
    @PostMapping("/artifacts/{artifactId}/apply-plan")
    public Map<String, Object> applyPlan(@RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable long projectId, @PathVariable String artifactId, @RequestBody ApprovalRequest request) {
        return approval.applyPlan(user(header), projectId, artifactId, request.confirmed(), request.expectedContentHash());
    }
    @PostMapping("/artifacts/{artifactId}/recover-synthesis")
    public Map<String, Object> recover(@RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable long projectId, @PathVariable String artifactId, @RequestBody RecoveryRequest request) {
        return approval.recoverSynthesis(user(header), projectId, artifactId, request.confirmed(), request.expectedContentHash(),
                request.expectedAudioId(), request.existingUrl());
    }
    @PostMapping("/artifacts/{artifactId}/retry-synthesis")
    public Map<String, Object> retry(@RequestHeader(value = "Authorization", required = false) String header,
            @PathVariable long projectId, @PathVariable String artifactId, @RequestBody RetryRequest request) {
        return approval.retrySynthesis(user(header), projectId, artifactId, request.confirmed(), request.expectedContentHash(),
                request.expectedAudioId(), request.reason());
    }
    private long user(String header) { return ((Number) auth.requireUser(header).get("id")).longValue(); }
    public record ApprovalRequest(boolean confirmed, String voice,
            @com.fasterxml.jackson.annotation.JsonProperty("expected_content_hash") String expectedContentHash,
            @com.fasterxml.jackson.annotation.JsonProperty("review_id") String reviewId,
            @com.fasterxml.jackson.annotation.JsonProperty("override_review") Boolean overrideReview,
            @com.fasterxml.jackson.annotation.JsonProperty("override_reason") String overrideReason) {
        public ApprovalRequest(boolean confirmed, String voice) { this(confirmed, voice, null, null, false, null); }
    }
    public record RecoveryRequest(boolean confirmed,
            @com.fasterxml.jackson.annotation.JsonProperty("expected_content_hash") String expectedContentHash,
            @com.fasterxml.jackson.annotation.JsonProperty("expected_audio_id") String expectedAudioId,
            @com.fasterxml.jackson.annotation.JsonProperty("existing_url") String existingUrl) { }
    public record RetryRequest(boolean confirmed,
            @com.fasterxml.jackson.annotation.JsonProperty("expected_content_hash") String expectedContentHash,
            @com.fasterxml.jackson.annotation.JsonProperty("expected_audio_id") String expectedAudioId, String reason) { }
}
