package com.bedouyu.audiorag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public record ProjectRequest(@NotBlank @Size(max = 500) String topic) { }
