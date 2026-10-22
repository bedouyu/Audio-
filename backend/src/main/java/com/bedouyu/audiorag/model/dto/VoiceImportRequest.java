package com.bedouyu.audiorag.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 音色录入请求：一段几秒的样例 WAV（base64）加上它对应的文字。
 * CosyVoice 用这两样做零样本克隆，不训练模型。
 */
public record VoiceImportRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String name,
        @NotBlank @Size(max = 80) @JsonProperty("display_name") String displayName,
        @NotEmpty @Size(max = 2) @JsonProperty("languages") List<@Pattern(regexp = "zh|en") String> languages,
        @NotBlank @Size(max = 300) @JsonProperty("prompt_text") String promptText,
        @NotBlank @Size(max = 12_000_000) @JsonProperty("wav_base64") String wavBase64) {
}
