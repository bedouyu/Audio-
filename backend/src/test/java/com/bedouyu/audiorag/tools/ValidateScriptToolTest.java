package com.bedouyu.audiorag.tools;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.rag.QdrantService.SearchHit;
import com.bedouyu.audiorag.service.AgentArtifactStore;
import com.bedouyu.audiorag.tools.audio.ValidateScriptTool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ValidateScriptToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentArtifactStore store = mock(AgentArtifactStore.class);
    private final ValidateScriptTool tool = new ValidateScriptTool(store);
    private final AgentExecutionContext context = new AgentExecutionContext("run", "session", "review_agent", 1, 7,
            new AgentBudget(), new EvidenceLedger(), Set.of("a"), null, ignored -> {});
    private final List<Map<String, Object>> citations = List.of(
            Map.of("id", "E1", "source_key", "old:key", "title", "来源", "source", "knowledge", "score", .9));

    @Test void lengthBoundariesAreCheckedAgainstTheStoredSource() {
        for (int length : List.of(39, 40, 24000, 24001)) {
            String source = "x".repeat(length - 4) + "[E1]"; artifact(source, citations);
            JsonNode result = check(source);
            assertThat(result.path("local_check_passed").asBoolean()).isEqualTo(length >= 40 && length <= 24000);
            assertThat(result.path("characters").asInt()).isEqualTo(length);
            assertThat(result.path("read_full").asBoolean()).isFalse();
        }
    }

    @Test void anUnknownSourceCitationCannotBorrowTheCurrentRunsSameNumber() {
        context.evidence.register(List.of(new SearchHit("live1", "来源1", "证据1", "knowledge", .9, null),
                new SearchHit("live2", "来源2", "证据2", "knowledge", .8, null)));
        String source = "x".repeat(45) + "[E2]"; artifact(source, List.of());
        JsonNode result = check(source);
        assertThat(result.path("local_check_passed").asBoolean()).isFalse();
        assertThat(result.path("issues").toString()).contains("缺少对应产物证据", "E2");
    }

    @Test void wrongHashDoesNotRecordALocalCheck() {
        String source = "x".repeat(45) + "[E1]"; artifact(source, citations);
        assertThatThrownBy(() -> tool.execute(json.valueToTree(Map.of("artifact_id", "a", "content_hash", "0".repeat(64))), context))
                .hasMessageContaining("原文不一致");
        assertThat(context.verification.result("a")).isNull();
    }

    @Test void checkReturnsTheSameSourceAndRenderedHashesAsItsSpecificReading() {
        context.evidence.register(List.of(new SearchHit("live", "新来源", "新证据", "knowledge", .9, null)));
        String source = "x".repeat(45) + "[E1]"; artifact(source, citations);
        String rendered = context.evidence.importArtifact(source, citations);
        context.verification.read("a", Hashing.sha256(source), Hashing.sha256(rendered), 0, rendered.length(), rendered.length());
        JsonNode result = check(source);
        assertThat(result.path("content_hash").asText()).isEqualTo(Hashing.sha256(source));
        assertThat(result.path("rendered_content_hash").asText()).isEqualTo(Hashing.sha256(rendered));
        assertThat(result.path("read_full").asBoolean()).isTrue();
        assertThat(context.verification.result("a").checked()).isTrue();
        assertThat(context.verification.result("a").localCheckPassed()).isTrue();
    }

    @Test void originalFictionCanPassLocalChecksWithoutInventedCitations() {
        String source = "At midnight the little fox discovered a lantern beneath the old bridge. It was still warm.";
        artifact(source, List.of());
        JsonNode result = check(source);
        assertThat(result.path("local_check_passed").asBoolean()).isTrue();
        assertThat(result.path("evidence_policy").asText()).isEqualTo("UNSOURCED_CONTENT_REQUIRES_CONTEXT_REVIEW");
    }

    @Test void aGroundedArtifactCannotAttachResearchWithoutLinkingItInTheScript() {
        String source = "x".repeat(80);
        artifact(source, citations);
        JsonNode result = check(source);
        assertThat(result.path("local_check_passed").asBoolean()).isFalse();
        assertThat(result.path("issues").toString()).contains("附有研究证据");
    }

    private JsonNode check(String source) {
        return json.valueToTree(tool.execute(json.valueToTree(Map.of("artifact_id", "a", "content_hash", Hashing.sha256(source))), context));
    }
    private void artifact(String source, List<Map<String, Object>> evidence) {
        when(store.get(1, 7, "a")).thenReturn(Map.of("kind", "SCRIPT_DRAFT", "content", source, "citations", evidence));
    }
}
