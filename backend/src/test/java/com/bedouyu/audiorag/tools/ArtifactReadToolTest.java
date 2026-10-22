package com.bedouyu.audiorag.tools;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.service.AgentArtifactStore;
import com.bedouyu.audiorag.tools.agent.ArtifactReadTool;
import com.bedouyu.audiorag.rag.QdrantService.SearchHit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ArtifactReadToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentArtifactStore store = mock(AgentArtifactStore.class);
    private final ArtifactReadTool tool = new ArtifactReadTool(store, json);
    private final AgentExecutionContext context = new AgentExecutionContext("run", "session", "review_agent", 1, 7,
            new AgentBudget(), new EvidenceLedger(), Set.of("a"), null, ignored -> {});

    @Test void jsonEscapingBudgetLimitsActualObservationAndRecordsOnlyTheReturnedSpan() {
        String source = String.valueOf((char) 0).repeat(6000);
        artifact(source, List.of());
        JsonNode page = read(0, 6000);
        String serialized = json.writeValueAsString(page);
        assertThat(serialized.length()).isLessThanOrEqualTo(7500);
        assertThat(json.readTree(serialized).path("status").asText()).isEqualTo("OK");
        assertThat(page.path("has_more").asBoolean()).isTrue();
        assertThat(page.path("read_full").asBoolean()).isFalse();
        assertThat(page.path("next_offset").asInt()).isEqualTo(page.path("content").asText().length());
        assertThat(context.verification.result("a").readSpans()).containsExactly(
                new com.bedouyu.audiorag.core.guard.ArtifactVerificationLedger.ReadSpan(0, page.path("next_offset").asInt()));
    }

    @Test void paginationPreservesSurrogatePairsAndEventuallyProvesFullReading() {
        String source = "x".repeat(99) + "😀" + "x".repeat(99) + "😀";
        artifact(source, List.of());
        JsonNode first = read(0, 100);
        assertThat(first.path("next_offset").asInt()).isEqualTo(99);
        assertThatThrownBy(() -> read(100, 100)).hasMessageContaining("范围无效");
        StringBuilder assembled = new StringBuilder(first.path("content").asText());
        JsonNode page = first;
        while (page.path("has_more").asBoolean()) {
            page = read(page.path("next_offset").asInt(), 100);
            String content = page.path("content").asText();
            assertThat(Character.isLowSurrogate(content.charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(content.charAt(content.length() - 1))).isFalse();
            assembled.append(content);
        }
        assertThat(assembled.toString()).isEqualTo(source);
        assertThat(page.path("read_full").asBoolean()).isTrue();
    }

    @Test void sourceHashIsStableWhileRenderedHashReflectsCitationRemapping() {
        context.evidence.register(List.of(new SearchHit("new", "新来源", "新证据", "knowledge", .9, null)));
        String source = "旧产物的结论 [E1]";
        artifact(source, List.of(Map.of("id", "E1", "source_key", "old:key", "title", "旧来源", "source", "knowledge", "score", .9)));
        JsonNode page = read(0, 100);
        assertThat(page.path("content").asText()).isEqualTo("旧产物的结论 [E2]");
        assertThat(page.path("content_hash").asText()).isEqualTo(Hashing.sha256(source));
        assertThat(page.path("source_content_hash").asText()).isEqualTo(Hashing.sha256(source));
        assertThat(page.path("rendered_content_hash").asText()).isEqualTo(Hashing.sha256("旧产物的结论 [E2]"));
    }

    private JsonNode read(int offset, int limit) {
        return json.valueToTree(tool.execute(json.valueToTree(Map.of("artifact_id", "a", "offset", offset, "limit", limit)), context));
    }
    private void artifact(String source, List<Map<String, Object>> citations) {
        when(store.get(1, 7, "a")).thenReturn(Map.of("kind", "SCRIPT_DRAFT", "status", "DRAFT", "content", source, "citations", citations));
    }
}
