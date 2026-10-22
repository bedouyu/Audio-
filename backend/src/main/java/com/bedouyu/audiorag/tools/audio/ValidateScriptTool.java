package com.bedouyu.audiorag.tools.audio;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.common.SpeechTiming;
import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.service.AgentArtifactStore;
import com.bedouyu.audiorag.tools.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

@Component
public class ValidateScriptTool implements FunctionTool {
    private final AgentArtifactStore store;
    public ValidateScriptTool(AgentArtifactStore store) { this.store = store; }
    public FunctionDefinition definition() { return new FunctionDefinition("validate_script",
            "对指定讲稿原文 SHA256 执行本机类型、引用编号、40—24000 字符长度与时长检查；content_hash 须使用 read_artifact 返回值。"
                    + "传入 target_minutes 时额外返回估算时长与偏差，并按语言密度判断是否达标。学术事实仍需阅读证据。",
            ToolSchemas.object(Map.of("artifact_id", ToolSchemas.string(36), "content_hash", ToolSchemas.string(64),
                    "target_minutes", ToolSchemas.integer(1, 60)), "artifact_id", "content_hash")); }
    @SuppressWarnings("unchecked")
    public Object execute(JsonNode args, AgentExecutionContext context) {
        String id = args.path("artifact_id").asText(); context.requireArtifact(id);
        var artifact = store.get(context.userId, context.projectId, id);
        context.assertActive();
        if (!"SCRIPT_DRAFT".equals(artifact.get("kind"))) throw new IllegalArgumentException("产物不是讲稿");
        String raw = String.valueOf(artifact.get("content")), rawHash = Hashing.sha256(raw);
        if (!rawHash.equals(args.path("content_hash").asText())) throw new IllegalArgumentException("检查内容校验值与目标讲稿原文不一致");
        List<Map<String, Object>> citations = (List<Map<String, Object>>) artifact.get("citations");
        String text = context.evidence.importArtifact(raw, citations);
        String renderedHash = Hashing.sha256(text);
        List<String> issues = new ArrayList<>(context.evidence.unknownCitations(text));
        Set<String> sourceIds = new HashSet<>();
        citations.forEach(citation -> sourceIds.add(String.valueOf(citation.get("id"))));
        var matcher = java.util.regex.Pattern.compile("\\[(E[0-9]+)\\]").matcher(raw);
        Set<String> missing = new LinkedHashSet<>();
        while (matcher.find()) if (!sourceIds.contains(matcher.group(1))) missing.add(matcher.group(1));
        if (!missing.isEmpty()) issues.add("讲稿引用缺少对应产物证据：" + missing);
        // Creation can intentionally have no sources (fiction, invented dialogue). Once the artifact
        // carries real research citations, the script must actually link its grounded claims to them.
        if (!citations.isEmpty() && !text.matches("(?s).*\\[E[0-9]+\\].*"))
            issues.add("讲稿附有研究证据但没有明确引用编号，请核查事实与来源的对应关系");
        if (raw.length() < 40 || raw.length() > 24000) issues.add("讲稿长度应为 40—24000 字符");
        // 时长是用户的硬要求：按讲稿自身语言密度估算，偏差超过 25% 记为问题。
        int targetMinutes = Math.max(0, args.path("target_minutes").asInt(0));
        double estimatedSeconds = SpeechTiming.estimatedSeconds(raw);
        Double deviation = null;
        if (targetMinutes > 0) {
            double targetSeconds = targetMinutes * 60.0;
            deviation = (estimatedSeconds - targetSeconds) / targetSeconds;
            // 单篇 24000 字符写不满更长时长，这种情况只报告不判错。
            if (Math.abs(deviation) > 0.25 && raw.length() < 23000)
                issues.add(String.format("讲稿估算约 %.1f 分钟，与目标的 %d 分钟相差 %d%%，请%s",
                        estimatedSeconds / 60.0, targetMinutes, Math.round(Math.abs(deviation) * 100),
                        deviation < 0 ? "扩写" : "压缩"));
        }
        context.assertActive();
        context.verification.check(id, rawHash, renderedHash, issues.isEmpty(), issues);
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "OK"); result.put("artifact_id", id); result.put("source_content_hash", rawHash);
        result.put("content_hash", rawHash); result.put("rendered_content_hash", renderedHash);
        result.put("read_full", context.verification.fullRead(id, rawHash, renderedHash));
        result.put("evidence_policy", citations.isEmpty() ? "UNSOURCED_CONTENT_REQUIRES_CONTEXT_REVIEW" : "GROUNDED_CITATIONS_REQUIRED");
        result.put("local_check_passed", issues.isEmpty()); result.put("issues", issues); result.put("characters", raw.length());
        result.put("characters_per_minute", Math.round(SpeechTiming.charactersPerMinute(raw)));
        result.put("estimated_seconds", Math.round(estimatedSeconds));
        if (deviation != null) {
            result.put("target_minutes", targetMinutes);
            result.put("deviation_ratio", Math.round(deviation * 100) / 100.0);
        }
        return result;
    }
}
