package com.bedouyu.audiorag.core;

import com.bedouyu.audiorag.core.loop.AgentBudget;
import com.bedouyu.audiorag.core.loop.AgentTranscript;
import com.bedouyu.audiorag.model.dto.AgentModelReply;
import com.bedouyu.audiorag.model.dto.FunctionCall;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TranscriptCompactionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void defaultStructuredCompactionKeepsRetrievalChoicesWarningsAndEvidenceCoordinates() {
        var evidence = Map.of("citation_id", "E17", "title", "概率论笔记", "source", "projects/7/notes.md",
                "text", "原文".repeat(30000), "location", Map.of("document_id", "document-17", "text_version", "v1",
                        "start_offset", 120, "end_offset", 360, "index_version", "index-v2"));
        var observation = json.writeValueAsString(Map.of("status", "OK", "evidence", List.of(evidence),
                "requested_retrieval", "hybrid", "actual_retrieval", "bm25", "requested_context", "neighbors",
                "actual_context", "top_k", "context_tokens_bge", 512, "hit_count", 1,
                "warnings", List.of("向量检索不可用，已降级为 BM25。")));
        var transcript = withOldObservation(observation, null);
        var messages = transcript.messages();
        var compacted = json.readTree(String.valueOf(messages.get(2).get("content")));
        assertThat(compacted.path("compaction_strategy").asText()).isEqualTo("structured");
        assertThat(compacted.path("status").asText()).isEqualTo("OK");
        assertThat(compacted.path("compaction_status").asText()).isEqualTo("COMPACTED");
        assertThat(compacted.path("requested_retrieval").asText()).isEqualTo("hybrid");
        assertThat(compacted.path("actual_retrieval").asText()).isEqualTo("bm25");
        assertThat(compacted.path("requested_context").asText()).isEqualTo("neighbors");
        assertThat(compacted.path("actual_context").asText()).isEqualTo("top_k");
        assertThat(compacted.path("context_tokens_bge").asInt()).isEqualTo(512);
        assertThat(compacted.path("hit_count").asInt()).isEqualTo(1);
        assertThat(compacted.path("warnings").get(0).asText()).contains("BM25");
        var retained = compacted.path("evidence").get(0);
        assertThat(retained.path("citation_id").asText()).isEqualTo("E17");
        assertThat(retained.path("title").asText()).isEqualTo("概率论笔记");
        assertThat(retained.path("source").asText()).isEqualTo("projects/7/notes.md");
        assertThat(retained.path("location").path("document_id").asText()).isEqualTo("document-17");
        assertThat(retained.path("location").path("start_offset").asInt()).isEqualTo(120);
        assertThat(retained.path("location").path("end_offset").asInt()).isEqualTo(360);
        assertThat(retained.has("text")).isFalse();
        assertThat(retained.path("content_compacted").asBoolean()).isTrue();
        assertProtocolAndLatest(messages);
    }

    @Test
    void structuredArtifactObservationKeepsExactIdAndPagination() {
        String id = "00000000-0000-0000-0000-000000000017";
        var observation = json.writeValueAsString(Map.of("status", "OK", "artifact_id", id,
                "kind", "SCRIPT_DRAFT", "artifact_status", "NEEDS_REVIEW", "content", "正文".repeat(30000),
                "offset", 4000, "next_offset", 8000, "total_chars", 9000, "has_more", true));
        var messages = withOldObservation(observation, "structured").messages();
        var compacted = json.readTree(String.valueOf(messages.get(2).get("content")));
        assertThat(compacted.path("artifact_id").asText()).isEqualTo(id);
        assertThat(compacted.path("kind").asText()).isEqualTo("SCRIPT_DRAFT");
        assertThat(compacted.path("artifact_status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(compacted.path("next_offset").asInt()).isEqualTo(8000);
        assertThat(compacted.path("total_chars").asInt()).isEqualTo(9000);
        assertThat(compacted.path("has_more").asBoolean()).isTrue();
        assertThat(compacted.path("content_compacted").asBoolean()).isTrue();
        assertThat(compacted.path("message").asText()).contains("artifact_id", "重新读取");
        assertProtocolAndLatest(messages);
    }

    @Test
    void structuredCompactionReportsOmittedEntriesAndProducesBoundedValidJson() {
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (int i = 0; i < 60; i++) evidence.add(Map.of("citation_id", "E" + i,
                "title", "标题\"\\\n".repeat(100), "source", "projects/7/document-" + i + ".md",
                "text", "正文".repeat(700), "location", Map.of("start_offset", i * 700, "end_offset", (i + 1) * 700)));
        var observation = json.writeValueAsString(Map.of("status", "OK", "evidence", evidence, "hit_count", 60));
        var messages = withOldObservation(observation, "structured").messages();
        String content = String.valueOf(messages.get(2).get("content"));
        var compacted = json.readTree(content);
        assertThat(content.length()).isLessThanOrEqualTo(1000);
        assertThat(compacted.path("hit_count").asInt()).isEqualTo(60);
        assertThat(compacted.path("evidence").size()).isBetween(1, 8);
        assertThat(compacted.path("evidence_omitted").asInt()).isEqualTo(60 - compacted.path("evidence").size());
        assertThat(compacted.path("omitted_entries").asInt()).isEqualTo(compacted.path("evidence_omitted").asInt());
        assertThat(compacted.path("metadata_truncated").asBoolean()).isTrue();
        assertProtocolAndLatest(messages);
    }

    @Test
    void previewIsExplicitlyRawAndEscapedWithinItsBudget() {
        String observation = "{\"text\":\"" + "\\\"\n\t".repeat(17000);
        var messages = withOldObservation(observation, "preview").messages();
        String content = String.valueOf(messages.get(2).get("content"));
        var compacted = json.readTree(content);
        assertThat(content.length()).isLessThanOrEqualTo(1000);
        assertThat(compacted.path("status").asText()).isEqualTo("COMPACTED");
        assertThat(compacted.path("compaction_strategy").asText()).isEqualTo("preview");
        assertThat(compacted.path("message").asText()).contains("原始文本前缀", "不是完整 JSON");
        assertThat(compacted.path("preview").asText()).startsWith("{\"text\":\"");
        assertThat(compacted.path("omitted_chars").asInt()).isPositive();
        assertProtocolAndLatest(messages);
    }

    @Test
    void malformedObservationFallsBackToMarkedPreview() {
        var messages = withOldObservation("非 JSON 工具输出".repeat(7000), "structured").messages();
        var compacted = json.readTree(String.valueOf(messages.get(2).get("content")));
        assertThat(compacted.path("status").asText()).isEqualTo("COMPACTED");
        assertThat(compacted.path("compaction_strategy").asText()).isEqualTo("preview");
        assertProtocolAndLatest(messages);
    }

    @Test
    void compactionRetainsEveryCallInMultiToolRounds() {
        var transcript = new AgentTranscript(List.of(), json, "structured");
        var assistant = new AgentModelReply("", "完整推理", List.of(new FunctionCall("c1", "search", "{}"),
                new FunctionCall("c2", "read_artifact", "{}")), "tool_calls").assistantMessage();
        transcript.addRound(assistant, List.of(tool("c1", json.writeValueAsString(Map.of("status", "OK", "text", "x".repeat(30000)))),
                tool("c2", json.writeValueAsString(Map.of("status", "OK", "artifact_id", "artifact-2", "content", "x".repeat(30000))))));
        transcript.addRound(reply("latest"), List.of(tool("latest", "latest observation")));
        var messages = transcript.messages();
        assertThat(messages.get(0)).isEqualTo(assistant);
        assertThat(messages.get(1).get("tool_call_id")).isEqualTo("c1");
        assertThat(messages.get(2).get("tool_call_id")).isEqualTo("c2");
        assertThat(json.readTree(String.valueOf(messages.get(1).get("content"))).path("compaction_status").asText()).isEqualTo("COMPACTED");
        assertThat(json.readTree(String.valueOf(messages.get(2).get("content"))).path("artifact_id").asText()).isEqualTo("artifact-2");
        assertThat(messages.get(4).get("content")).isEqualTo("latest observation");
    }

    @Test
    void stopsInsteadOfTruncatingRequiredReasoningOrTheLatestRound() {
        var transcript = new AgentTranscript(List.of(), json);
        var assistant = new AgentModelReply("", "r".repeat(50000), List.of(new FunctionCall("old", "search", "{}")),
                "tool_calls").assistantMessage();
        transcript.addRound(assistant, List.of(tool("old", "x".repeat(8000))));
        transcript.addRound(reply("latest"), List.of(tool("latest", latestObservation())));
        assertThatThrownBy(transcript::messages).isInstanceOf(AgentBudget.AgentStopped.class);
        assertThat(assistant.get("reasoning_content")).isEqualTo("r".repeat(50000));
    }

    @Test
    void rejectsUnknownCompactionStrategy() {
        assertThatThrownBy(() -> new AgentTranscript(List.of(), json, "unknown")).isInstanceOf(IllegalArgumentException.class);
    }

    private AgentTranscript withOldObservation(String observation, String strategy) {
        var initial = List.<Map<String, Object>>of(Map.of("role", "system", "content", "sys"));
        var transcript = strategy == null ? new AgentTranscript(initial, json) : new AgentTranscript(initial, json, strategy);
        transcript.addRound(reply("old"), List.of(tool("old", observation)));
        transcript.addRound(reply("latest"), List.of(tool("latest", latestObservation())));
        return transcript;
    }
    private void assertProtocolAndLatest(List<Map<String, Object>> messages) {
        assertThat(json.writeValueAsString(messages).length()).isLessThanOrEqualTo(48000);
        assertThat(String.valueOf(messages.get(2).get("content")).length()).isLessThanOrEqualTo(1000);
        assertThat(messages.get(1)).isEqualTo(reply("old"));
        assertThat(messages.get(2).get("tool_call_id")).isEqualTo("old");
        assertThat(messages.get(3)).isEqualTo(reply("latest"));
        assertThat(messages.get(4)).isEqualTo(tool("latest", latestObservation()));
    }
    private Map<String, Object> reply(String id) {
        return new AgentModelReply("", "reasoning-" + id, List.of(new FunctionCall(id, "search", "{\"query\":\"test\"}")),
                "tool_calls").assistantMessage();
    }
    private Map<String, Object> tool(String id, String content) { return Map.of("role", "tool", "tool_call_id", id, "content", content); }
    private String latestObservation() { return json.writeValueAsString(Map.of("status", "OK", "text", "最新完整原文".repeat(400))); }
}
