package com.bedouyu.audiorag.core;

import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.agent.AgentProfile;
import com.bedouyu.audiorag.model.dto.*;
import com.bedouyu.audiorag.tools.*;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AgentRuntimeGuardTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test
    void rejectsCrossProjectParametersAndToolsOutsideAgentPermissions() {
        FunctionTool allowed = mock(FunctionTool.class);
        when(allowed.definition()).thenReturn(new FunctionDefinition("knowledge_search", "检索",
                ToolSchemas.object(Map.of("query", ToolSchemas.string(1000)), "query")));
        var registry = new FunctionToolRegistry(List.of(), List.of(allowed));
        var repository = mock(AudioRagRepository.class);
        when(repository.ownsProject(1, 7)).thenReturn(true);
        var executor = new FunctionToolExecutor(registry, new ToolArgumentValidator(), repository, json);
        var profile = new AgentProfile("research_agent", "研究", "", Set.of("knowledge_search"), "", 5);
        var context = context();
        String invalid = executor.execute(new FunctionCall("c1", "knowledge_search", "{\"query\":\"test\",\"project_id\":99}"), profile, context);
        assertThat(json.readTree(invalid).path("status").asText()).isEqualTo("ERROR");
        String unknown = executor.execute(new FunctionCall("c2", "delete_project", "{}"), profile, context);
        assertThat(unknown).contains("无此工具权限");
        verify(allowed, never()).execute(any(), any());
    }
    @Test
    void repeatedArgumentsAreLimitedEvenWhenKeyOrderChanges() {
        FunctionTool tool = mock(FunctionTool.class);
        when(tool.definition()).thenReturn(new FunctionDefinition("search", "检索",
                ToolSchemas.object(Map.of("query", ToolSchemas.string(50), "limit", ToolSchemas.integer(1, 4)), "query")));
        when(tool.execute(any(), any())).thenReturn(Map.of("status", "OK"));
        var repository = mock(AudioRagRepository.class);
        when(repository.ownsProject(1, 7)).thenReturn(true);
        var executor = new FunctionToolExecutor(new FunctionToolRegistry(List.of(), List.of(tool)),
                new ToolArgumentValidator(), repository, json);
        var profile = new AgentProfile("research", "研究", "", Set.of("search"), "", 6);
        var context = context();
        executor.execute(new FunctionCall("c1", "search", "{\"query\":\"test\",\"limit\":2}"), profile, context);
        executor.execute(new FunctionCall("c2", "search", "{\"limit\":2,\"query\":\"test\"}"), profile, context);
        assertThatThrownBy(() -> executor.execute(new FunctionCall("c3", "search", "{\"query\":\"test\",\"limit\":2}"), profile, context))
                .isInstanceOf(AgentBudget.AgentStopped.class);
        verify(tool, times(2)).execute(any(), any());
    }
    @Test
    void compactionPreservesWholeToolProtocolAndDoesNotDiscardReasoning() {
        var transcript = new AgentTranscript(List.of(Map.of("role", "system", "content", "sys")), json);
        for (int i = 0; i < 7; i++) {
            var reply = new AgentModelReply("", "retained-" + i,
                    List.of(new FunctionCall("call-" + i, "search", "{}")), "tool_calls");
            transcript.addRound(reply.assistantMessage(), List.of(Map.of(
                    "role", "tool", "tool_call_id", "call-" + i, "content", "x".repeat(8000))));
        }
        var messages = transcript.messages();
        assertThat(json.writeValueAsString(messages).length()).isLessThanOrEqualTo(48000);
        for (int i = 0; i < 7; i++) {
            assertThat(messages.get(1 + 2 * i).get("reasoning_content")).isEqualTo("retained-" + i);
            assertThat(messages.get(2 + 2 * i).get("tool_call_id")).isEqualTo("call-" + i);
        }
    }
    @Test
    void oldArtifactCitationsAreRebasedWithoutCrossRunNumberCollision() {
        var ledger = new EvidenceLedger();
        ledger.register(List.of(new com.bedouyu.audiorag.rag.QdrantService.SearchHit("point", "新来源", "证据", "new", .9, null)));
        String imported = ledger.importArtifact("旧结论 [E1]", List.of(Map.of(
                "id", "E1", "source_key", "old:key", "title", "旧来源", "source", "old", "score", .8)));
        assertThat(imported).isEqualTo("旧结论 [E2]");
        assertThat(ledger.unknownCitations(imported)).isEmpty();
    }
    @Test
    void childCannotReadArtifactsThatWereNotExplicitlyPassed() {
        assertThatThrownBy(() -> context().child("writer", Set.of("allowed")).requireArtifact("other"))
                .hasMessageContaining("没有传入");
    }
    private AgentExecutionContext context() {
        return new AgentExecutionContext("run", "session", "research", 1, 7,
                new AgentBudget(10, 10, Duration.ofMinutes(1)), new EvidenceLedger(), null, null, ignored -> { });
    }
}
