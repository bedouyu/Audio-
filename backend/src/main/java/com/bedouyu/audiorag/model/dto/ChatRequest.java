package com.bedouyu.audiorag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public record ChatRequest(@NotBlank @Size(max = 4000) String message,
        @JsonProperty("session_id") String sessionId,
        @NotNull @JsonProperty("project_id") Long projectId,
        @Pattern(regexp = "recent_window|query_recall|extractive_notes|hybrid")
        @JsonProperty("memory_strategy") String memoryStrategy,
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        @JsonProperty("request_id") String requestId,
        @Size(max = 4) @JsonProperty("required_artifacts") java.util.Set<@Pattern(regexp = "LEARNING_PLAN|RESEARCH_REPORT|SCRIPT_DRAFT|REVIEW_REPORT") String> requiredArtifacts,
        @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        @JsonProperty("audio_queue_id") String audioQueueId) {
    public ChatRequest(String message, String sessionId, Long projectId, String memoryStrategy,
                       String requestId, java.util.Set<String> requiredArtifacts) {
        this(message, sessionId, projectId, memoryStrategy, requestId, requiredArtifacts, null);
    }
    public ChatRequest(String message, String sessionId, Long projectId) { this(message, sessionId, projectId, null, null, null); }
    public ChatRequest(String message, String sessionId, Long projectId, String memoryStrategy) { this(message, sessionId, projectId, memoryStrategy, null, null); }
}
