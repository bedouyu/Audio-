package com.bedouyu.audiorag.tools.audio;
import com.bedouyu.audiorag.common.Hashing;
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
            "对指定讲稿原文 SHA256 执行本机类型、引用编号与 40—24000 字符长度检查；content_hash 须使用 read_artifact 返回值。学术事实仍需阅读证据。",
            ToolSchemas.object(Map.of("artifact_id", ToolSchemas.string(36), "content_hash", ToolSchemas.string(64)), "artifact_id", "content_hash")); }
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
        context.assertActive();
        context.verification.check(id, rawHash, renderedHash, issues.isEmpty(), issues);
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "OK"); result.put("artifact_id", id); result.put("source_content_hash", rawHash);
        result.put("content_hash", rawHash); result.put("rendered_content_hash", renderedHash);
        result.put("read_full", context.verification.fullRead(id, rawHash, renderedHash));
        result.put("evidence_policy", citations.isEmpty() ? "UNSOURCED_CONTENT_REQUIRES_CONTEXT_REVIEW" : "GROUNDED_CITATIONS_REQUIRED");
        result.put("local_check_passed", issues.isEmpty()); result.put("issues", issues); result.put("characters", raw.length());
        result.put("estimated_seconds", Math.round(raw.length() * 60.0 / 260)); return result;
    }
}
