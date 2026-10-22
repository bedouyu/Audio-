package com.bedouyu.audiorag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public record LessonRequest(@NotBlank String topic,
        @NotNull @JsonProperty("project_id") Long projectId,
        @Min(1) @Max(30) @JsonProperty("duration_minutes") int durationMinutes,
        String audience, String voice,
        @Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        @JsonProperty("request_id") String requestId) {
    public LessonRequest(String topic, Long projectId, int durationMinutes, String audience, String voice) {
        this(topic, projectId, durationMinutes, audience, voice, null);
    }
}
