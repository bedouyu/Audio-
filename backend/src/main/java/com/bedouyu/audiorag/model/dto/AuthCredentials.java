package com.bedouyu.audiorag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public record AuthCredentials(@NotBlank @Size(min = 2, max = 40) String username,
        @NotBlank @Size(min = 6, max = 100) String password) { }
