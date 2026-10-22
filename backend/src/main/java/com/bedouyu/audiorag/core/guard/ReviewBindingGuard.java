package com.bedouyu.audiorag.core.guard;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.service.AgentArtifactStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** A review can only describe the exact script the reviewer read and checked. */
@Component
public class ReviewBindingGuard {
    /**
     * 复核必须逐项表态，而不是给一个含糊的 approved：
     * 主题、体裁、语言、结尾、篇幅、引用、可朗读性。
     */
    public static final List<String> CHECKS = List.of(
            "topic", "genre", "language", "ending", "length", "citations", "readable");
    private final AgentArtifactStore artifacts;
    private final ObjectMapper json;

    public ReviewBindingGuard(AgentArtifactStore artifacts, ObjectMapper json) {
        this.artifacts = artifacts; this.json = json;
    }

    @SuppressWarnings("unchecked")
    public void validate(String content, AgentExecutionContext context) {
        var root = json.readTree(content);
        if (!root.isObject() || !root.path("reviewed_artifact_id").isTextual()
                || root.path("reviewed_artifact_id").asText().isBlank()
                || !root.path("content_hash").isTextual() || !root.path("content_hash").asText().matches("[0-9a-f]{64}")
                || !root.path("approved").isBoolean() || !root.path("issues").isArray()
                || root.path("issues").size() > 20 || !root.path("reason").isTextual()
                || root.path("reason").asText().isBlank())
            throw new IllegalArgumentException("复核报告必须包含 reviewed_artifact_id、content_hash、approved 布尔值、issues 数组和 reason 的 JSON 对象");
        for (var issue : root.path("issues"))
            if (!issue.isTextual() || issue.asText().isBlank()) throw new IllegalArgumentException("复核问题必须是具体的非空字符串");

        // 逐项结论：缺项视为没做检查；任一项不通过就不能声称通过。
        var checks = root.path("checks");
        if (!checks.isObject()) throw new IllegalArgumentException("复核报告必须包含 checks 对象，逐项给出结论");
        boolean allMet = true;
        for (String key : CHECKS) {
            if (!checks.path(key).isBoolean())
                throw new IllegalArgumentException("复核 checks 缺少布尔项：" + key);
            if (!checks.path(key).asBoolean()) allMet = false;
        }
        if (!allMet && root.path("approved").asBoolean())
            throw new IllegalArgumentException("checks 有未通过项时不能 approved=true；请把具体问题写进 issues 并把 approved 置为 false");

        String id = root.path("reviewed_artifact_id").asText();
        if (context.readableArtifacts == null || !context.readableArtifacts.contains(id))
            throw new IllegalArgumentException("复核目标没有显式传入当前 Agent");
        var artifact = artifacts.get(context.userId, context.projectId, id);
        if (!"SCRIPT_DRAFT".equals(artifact.get("kind"))) throw new IllegalArgumentException("复核目标必须是讲稿产物");
        String raw = String.valueOf(artifact.get("content"));
        String rawHash = Hashing.sha256(raw);
        if (!rawHash.equals(root.path("content_hash").asText())) throw new IllegalArgumentException("复核内容校验值与目标讲稿原文不一致");
        String rendered = context.evidence.importArtifact(raw, (List<Map<String, Object>>) artifact.get("citations"));
        String renderedHash = Hashing.sha256(rendered);
        if (!context.verification.fullRead(id, rawHash, renderedHash))
            throw new IllegalArgumentException("复核必须分段读完同一内容版本的完整目标讲稿");
        var checked = context.verification.result(id);
        if (checked == null || !checked.checked() || !checked.rawHash().equals(rawHash) || !checked.renderedHash().equals(renderedHash))
            throw new IllegalArgumentException("复核必须完成同一目标讲稿的本机检查");
        if (root.path("approved").asBoolean() && (!checked.localCheckPassed() || !root.path("issues").isEmpty()))
            throw new IllegalArgumentException("本机检查失败或仍有复核问题，不能声称复核通过");
    }
}
