package com.bedouyu.audiorag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public record ContinuousRequest(@NotNull @JsonProperty("project_id") Long projectId,
        @Min(10) @Max(60) @JsonProperty("course_minutes") int courseMinutes,
        @Min(1) @Max(10) @JsonProperty("segment_minutes") int segmentMinutes,
        @Min(1) @Max(3) @JsonProperty("prefetch_size") int prefetchSize,
        String audience, String voice,
        @Pattern(regexp="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        @JsonProperty("request_id") String requestId) {
    public ContinuousRequest(Long projectId, int courseMinutes, int segmentMinutes, int prefetchSize, String audience, String voice) {
        this(projectId, courseMinutes, segmentMinutes, prefetchSize, audience, voice, null);
    }
}
