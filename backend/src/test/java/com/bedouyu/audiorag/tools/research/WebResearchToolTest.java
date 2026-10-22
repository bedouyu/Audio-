package com.bedouyu.audiorag.tools.research;

import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.rag.QdrantService.SearchHit;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WebResearchToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final WebSearchService web = mock(WebSearchService.class);
    private final WebResearchTool tool = new WebResearchTool(web);
    private final AgentExecutionContext context = new AgentExecutionContext("run", "session", "research_agent", 1, 7,
            new AgentBudget(), new EvidenceLedger(), null, null, ignored -> { });

    @Test void newsQueryForcesLiveDatedSearchEvenWithoutModelFreshFlag() {
        when(web.enabled()).thenReturn(true);
        Instant now = Instant.now();
        when(web.searchFresh(anyString(), eq(4), any(), any())).thenReturn(List.of(new SearchHit("web", "新闻来源", "摘要",
                "https://publisher.test/news", .5, json.createObjectNode().put("published_at", now.toString())
                        .put("fetched_at", now.toString()).put("evidence_kind", "live-news-search-summary"))));
        var result = json.valueToTree(tool.execute(json.valueToTree(Map.of("query", "今天的新闻")), context));
        assertThat(result.path("fresh").asBoolean()).isTrue();
        assertThat(result.path("evidence").get(0).path("location").path("published_at").asText()).isNotBlank();
        verify(web, never()).search(anyString(), anyInt());
    }

    @Test void emptyNewsObservationsExplicitlyCannotEstablishFreshFacts() {
        when(web.enabled()).thenReturn(true);
        when(web.searchFresh(anyString(), anyInt(), any(), any())).thenReturn(List.of());
        var result = json.valueToTree(tool.execute(json.valueToTree(Map.of("query", "latest headlines", "fresh", true)), context));
        assertThat(result.path("status").asText()).isEqualTo("NO_RESULTS");
        assertThat(result.path("warning").asText()).contains("不能宣称");
    }

    @Test void genericResearchKeepsGeneralWebSearchAndDisabledWebIsAnExplicitError() {
        when(web.enabled()).thenReturn(true);
        when(web.search(anyString(), anyInt())).thenReturn(List.of());
        tool.execute(json.valueToTree(Map.of("query", "什么是二叉树")), context);
        verify(web).search("什么是二叉树", 4);
        verify(web, never()).searchFresh(anyString(), anyInt(), any(), any());
        when(web.enabled()).thenReturn(false);
        assertThatThrownBy(() -> tool.execute(json.valueToTree(Map.of("query", "新闻")), context)).hasMessageContaining("未启用");
    }
}
