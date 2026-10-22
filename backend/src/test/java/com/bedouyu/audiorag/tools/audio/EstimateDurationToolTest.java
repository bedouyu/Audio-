package com.bedouyu.audiorag.tools.audio;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EstimateDurationToolTest {
    private final EstimateDurationTool tool = new EstimateDurationTool();
    private final ObjectMapper json = new ObjectMapper();

    @Test void chineseOutlineHitsTheRequestedMinutesAndReportsTheTargetLength() throws Exception {
        var result = run("字".repeat(1950), 10);

        assertThat(result).containsEntry("status", "OK").containsEntry("target_minutes", 10);
        assertThat(result).containsEntry("within_tolerance", true);
        assertThat(number(result, "estimated_seconds")).isEqualTo(600L);
        assertThat(number(result, "target_characters")).isEqualTo(1950L);
        assertThat(number(result, "characters_per_minute")).isEqualTo(195L);
    }

    @Test void englishOutlineIsMeasuredInWordsSoTheSameWordCountIsNotFiveTimesTooLong() throws Exception {
        var result = run("word ".repeat(750).trim(), 5);

        assertThat(number(result, "estimated_seconds")).isEqualTo(300L);
        assertThat(number(result, "latin_words")).isEqualTo(750L);
        assertThat(number(result, "characters_per_minute")).isEqualTo(600L);
        assertThat(result).containsEntry("within_tolerance", true);
    }

    @Test void aShortScriptIsFlaggedAsBelowTargetWithAnActionableDeviation() throws Exception {
        var result = run("字".repeat(100), 10);

        assertThat(result).containsEntry("within_tolerance", false);
        assertThat(number(result, "target_characters")).isEqualTo(1950L);
        assertThat(((Number) result.get("deviation_ratio")).doubleValue()).isLessThan(-0.9);
    }

    private Map<String, Object> run(String text, int minutes) throws Exception {
        var args = json.readTree(json.writeValueAsString(Map.of("text", text, "target_minutes", minutes)));
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) tool.execute(args, null);
        return result;
    }

    private long number(Map<String, Object> result, String key) {
        return ((Number) result.get(key)).longValue();
    }
}
