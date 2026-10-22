package com.bedouyu.audiorag.tools.agent;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.service.AgentArtifactStore;
import com.bedouyu.audiorag.tools.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import java.util.*;

@Component
public class ArtifactReadTool implements FunctionTool {
    private final AgentArtifactStore store;
    private final ObjectMapper json;
    public ArtifactReadTool(AgentArtifactStore store, ObjectMapper json) { this.store = store; this.json = json; }
    public FunctionDefinition definition() { return new FunctionDefinition("read_artifact",
            "按编号分段读取产物；offset 为 UTF-16 字符偏移，limit 最大 6000。按 next_offset 读到 has_more=false；content_hash 是原文 SHA256，复核和本机检查须原样引用。",
            ToolSchemas.object(Map.of("artifact_id", ToolSchemas.string(36), "offset", ToolSchemas.integer(0, 32000),
                    "limit", ToolSchemas.integer(100, 6000)), "artifact_id")); }
    @SuppressWarnings("unchecked")
    public Object execute(JsonNode args, AgentExecutionContext context) {
        String id = args.path("artifact_id").asText(); context.requireArtifact(id);
        var artifact = store.get(context.userId, context.projectId, id);
        context.assertActive();
        String raw = String.valueOf(artifact.get("content"));
        String content = context.evidence.importArtifact(raw,
                (List<Map<String, Object>>) artifact.get("citations"));
        String rawHash = Hashing.sha256(raw), renderedHash = Hashing.sha256(content);
        int from = args.path("offset").asInt(0), limit = args.path("limit").asInt(4000);
        if (from < 0 || from > content.length() || boundary(content, from) || limit < 100 || limit > 6000)
            throw new IllegalArgumentException("产物阅读范围无效");
        int to = Math.min(content.length(), from + limit);
        if (boundary(content, to)) to--;
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "OK"); result.put("artifact_id", id); result.put("kind", artifact.get("kind"));
        result.put("source_content_hash", rawHash); result.put("content_hash", rawHash); result.put("rendered_content_hash", renderedHash);
        result.put("offset", from); result.put("total_chars", content.length()); result.put("artifact_status", artifact.get("status"));
        result.put("audio_url", artifact.get("audio_url") == null ? "" : artifact.get("audio_url"));
        result.put("read_full", false);
        page(result, content, from, to);
        while (json.writeValueAsString(result).length() > 7500) {
            int next = from + (to - from) / 2;
            if (boundary(content, next)) next--;
            if (next <= from) throw new IllegalArgumentException("产物元数据超出观察预算");
            to = next; page(result, content, from, to);
        }
        context.assertActive();
        context.verification.read(id, rawHash, renderedHash, from, to, content.length());
        result.put("read_full", context.verification.fullRead(id, rawHash, renderedHash));
        return result;
    }
    private static void page(Map<String, Object> result, String content, int from, int to) {
        result.put("content", content.substring(from, to)); result.put("next_offset", to); result.put("has_more", to < content.length());
    }
    private static boolean boundary(String value, int position) {
        return position > 0 && position < value.length() && Character.isHighSurrogate(value.charAt(position - 1))
                && Character.isLowSurrogate(value.charAt(position));
    }
}
