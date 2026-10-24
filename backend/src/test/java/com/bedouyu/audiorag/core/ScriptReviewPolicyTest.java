package com.bedouyu.audiorag.core;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.core.guard.ScriptReviewPolicy;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class ScriptReviewPolicyTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Object> script = Map.of("id", "script", "run_id", "run", "kind", "SCRIPT_DRAFT", "content", "当前讲稿");

    @Test void legacyTimestampTiesFailClosedRegardlessOfReportOrderOrConclusion() {
        var timestamp = Instant.parse("2026-10-24T00:00:00.123Z");
        var passed = review("passed", true, timestamp);
        var rejected = review("rejected", false, timestamp);
        for (var outputs : List.of(List.of(passed, rejected), List.of(rejected, passed),
                List.of(passed, review("also-passed", true, timestamp)))) {
            assertThat(ScriptReviewPolicy.approvedReviewFor(outputs, script, json)).isEmpty();
            assertThat(ScriptReviewPolicy.boundReviewFor(outputs, script, json)).isEmpty();
        }
    }

    @Test void uniqueLatestReportAndFixturesWithoutTimestampsRemainUsable() {
        var first = review("first", false, Instant.parse("2026-10-24T00:00:00.123Z"));
        var latest = review("latest", true, Instant.parse("2026-10-24T00:00:00.124Z"));
        assertThat(ScriptReviewPolicy.approvedReviewFor(List.of(first, latest), script, json).orElseThrow().get("id"))
                .isEqualTo("latest");
        var untimed = new LinkedHashMap<>(latest);
        untimed.remove("created_at");
        assertThat(ScriptReviewPolicy.approvedReviewFor(List.of(untimed), script, json)).isPresent();
    }

    private Map<String, Object> review(String id, boolean approved, Instant time) {
        return Map.of("id", id, "run_id", "run", "kind", "REVIEW_REPORT", "created_at", time,
                "content", json.writeValueAsString(Map.of("reviewed_artifact_id", "script",
                        "content_hash", Hashing.sha256("当前讲稿"), "approved", approved, "reason", "逐项核查结果",
                        "issues", approved ? List.of() : List.of("需要修订"), "checks", Map.of("topic", true,
                                "genre", true, "language", true, "ending", true, "length", true, "citations", true, "readable", true))));
    }
}
