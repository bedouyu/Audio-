package com.bedouyu.audiorag.tools;

import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.rag.*;
import com.bedouyu.audiorag.tools.research.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdaptiveKnowledgeSearchToolTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void nativeFunctionDefinitionReplacesLegacyAdapterAndRejectsUnknownStrategies() {
        var retrieval = mock(AdaptiveKnowledgeRetriever.class);
        var tool = new AdaptiveKnowledgeSearchTool(retrieval);
        var legacy = mock(KnowledgeSearchTool.class);
        when(legacy.id()).thenReturn("knowledge_search");
        var registry = new FunctionToolRegistry(List.of(legacy), List.of(tool));
        assertThat(registry.require("knowledge_search")).isSameAs(tool);
        var validator = new ToolArgumentValidator();
        assertThatThrownBy(() -> validator.validate(json.readTree("{\"query\":\"x\",\"retrieval_strategy\":\"shell\"}"),
                tool.definition().parameters())).hasMessageContaining("不在可选");
        assertThatThrownBy(() -> validator.validate(json.readTree("{\"query\":\"x\",\"project_id\":8}"),
                tool.definition().parameters())).hasMessageContaining("不允许");
    }
    @Test void decisionArgumentsExecuteInTrustedProjectAndEvidenceRetainsCompleteSegment() {
        var retrieval = mock(AdaptiveKnowledgeRetriever.class);
        var tool = new AdaptiveKnowledgeSearchTool(retrieval);
        String text = "完整证据".repeat(300); // >旧1000字符上限
        var hit = new QdrantService.SearchHit("doc:0-1200", "参考", text, "source.md", 1,
                json.valueToTree(Map.of("document_id", "doc", "start_offset", 0, "end_offset", text.length(), "text_version", "v1")));
        when(retrieval.search(7, "多步骤", "hybrid", "rse_rank_proxy", 1024, 4, List.of()))
                .thenReturn(new AdaptiveKnowledgeRetriever.Result("hybrid", "rse_rank_proxy", 10, 900, List.of(hit), List.of()));
        var ledger = new EvidenceLedger();
        var context = new AgentExecutionContext("run", "session", "research_agent", 1, 7,
                new AgentBudget(), ledger, Set.of(), null, ignored -> {});
        var result = json.valueToTree(tool.execute(json.readTree("{\"query\":\"多步骤\",\"retrieval_strategy\":\"hybrid\","
                + "\"context_strategy\":\"rse_rank_proxy\",\"strategy_reason\":\"需要连续证据\"}"), context));
        assertThat(result.path("actual_context").asText()).isEqualTo("rse_rank_proxy");
        assertThat(result.path("evidence").get(0).path("text").asText()).isEqualTo(text);
        assertThat(result.path("evidence").get(0).path("truncated").asBoolean()).isFalse();
        assertThat(ledger.citations().get(0)).containsKey("location");
    }
}
