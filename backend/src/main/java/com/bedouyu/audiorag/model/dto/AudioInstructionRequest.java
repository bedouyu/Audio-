package com.bedouyu.audiorag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/** A complete instruction, with optional context; no content module is required. */
public record AudioInstructionRequest(@NotBlank @Size(max = 5000) String instruction,
        @NotBlank @JsonProperty("request_id") String requestId,
        @Positive @JsonProperty("project_id") Long projectId,
        @Positive @JsonProperty("source_record_id") Long sourceRecordId,
        @Size(max = 200) String voice,
        @JsonProperty("previous_queue_id") String previousQueueId) {
    public AudioInstructionRequest(String instruction, String requestId, Long projectId, Long sourceRecordId, String voice) {
        this(instruction, requestId, projectId, sourceRecordId, voice, null);
    }
}
