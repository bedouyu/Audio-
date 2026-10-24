package com.bedouyu.audiorag.core.guard;

import com.bedouyu.audiorag.common.Hashing;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** 交付与消费都必须使用当前讲稿内容对应的最后一份复核结论。 */
public final class ScriptReviewPolicy {
    private ScriptReviewPolicy() { }

    public static Optional<Map<?, ?>> approvedReviewFor(List<?> outputs, Map<?, ?> script, ObjectMapper json) {
        return reviewFor(outputs, script, json, true);
    }

    /** 聊天可返回未通过的复核供用户修订，但报告仍必须绑定当前稿件。 */
    public static Optional<Map<?, ?>> boundReviewFor(List<?> outputs, Map<?, ?> script, ObjectMapper json) {
        return reviewFor(outputs, script, json, false);
    }

    private static Optional<Map<?, ?>> reviewFor(List<?> outputs, Map<?, ?> script, ObjectMapper json,
                                                 boolean requireApproval) {
        if (!"SCRIPT_DRAFT".equals(script.get("kind")) || !(script.get("id") instanceof String id)
                || !(script.get("run_id") instanceof String run) || !(script.get("content") instanceof String content)
                || content.isBlank()) return Optional.empty();
        String hash = Hashing.sha256(content);
        for (int index = outputs.size() - 1; index >= 0; index--) {
            if (!(outputs.get(index) instanceof Map<?, ?> review) || !"REVIEW_REPORT".equals(review.get("kind"))
                    || !run.equals(review.get("run_id")) || !(review.get("content") instanceof String report)) continue;
            try {
                var root = json.readTree(report);
                if (!id.equals(root.path("reviewed_artifact_id").asText())
                        || !hash.equals(root.path("content_hash").asText())) continue;
                var timestamp = createdAt(review);
                if (timestamp != null && ambiguousTimestamp(outputs, review, run, id, hash, timestamp, json))
                    return Optional.empty();
                boolean valid = root.isObject() && root.path("approved").isBoolean()
                        && root.path("issues").isArray() && root.path("issues").size() <= 20
                        && root.path("reason").isTextual() && !root.path("reason").asText("").isBlank()
                        && root.path("checks").isObject();
                boolean allMet = true;
                for (String key : ReviewBindingGuard.CHECKS) {
                    valid &= root.path("checks").path(key).isBoolean();
                    allMet &= root.path("checks").path(key).asBoolean();
                }
                for (var issue : root.path("issues")) valid &= issue.isTextual() && !issue.asText().isBlank();
                boolean approved = root.path("approved").asBoolean();
                valid &= !approved || allMet && root.path("issues").isEmpty();
                return valid && (!requireApproval || approved) ? Optional.of(review) : Optional.empty();
            } catch (RuntimeException malformed) {
                // 无法解析的旧报告不能作为通过凭证。
            }
        }
        return Optional.empty();
    }

    private static boolean ambiguousTimestamp(List<?> outputs, Map<?, ?> selected, String run, String id,
            String hash, Instant timestamp, ObjectMapper json) {
        for (var output : outputs) {
            if (!(output instanceof Map<?, ?> other) || other == selected
                    || !"REVIEW_REPORT".equals(other.get("kind")) || !run.equals(other.get("run_id"))
                    || !timestamp.equals(createdAt(other)) || !(other.get("content") instanceof String report)) continue;
            try {
                var root = json.readTree(report);
                if (id.equals(root.path("reviewed_artifact_id").asText()) && hash.equals(root.path("content_hash").asText()))
                    return true;
            } catch (RuntimeException malformed) { /* 无法识别绑定关系的报告不能改变结论。 */ }
        }
        return false;
    }

    private static Instant createdAt(Map<?, ?> artifact) {
        var value = artifact.get("created_at");
        if (value instanceof Instant instant) return instant;
        if (value instanceof String text) try { return Instant.parse(text); } catch (RuntimeException ignored) { }
        return null;
    }
}
