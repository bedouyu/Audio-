package com.bedouyu.audiorag.core;

import com.bedouyu.audiorag.agent.AgentProfile;
import com.bedouyu.audiorag.core.guard.AgentOutputValidator;
import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.rag.QdrantService.SearchHit;
import com.bedouyu.audiorag.service.AgentArtifactStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FreshAudioEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.now();
    private final LocalDate day = now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();

    @Test void liveNewsTimeMetadataSurvivesTheEvidenceLedger() {
        var ledger = new EvidenceLedger();
        var observation = ledger.register(List.of(hit("live", now, "live-news-search-summary")));
        assertThat(String.valueOf(observation.get(0).get("location"))).contains("published_at", "fetched_at");
        assertThat(ledger.hasFreshNewsEvidence(day, day)).isTrue();
    }

    @Test void ordinaryWebSnippetsAndImportedHistoricalCitationsCannotMasqueradeAsThisRunsNews() {
        var ledger = new EvidenceLedger();
        ledger.register(List.of(hit("ordinary", now, "live-web-search-summary"),
                hit("old", now.minusSeconds(3600), "live-news-search-summary")));
        assertThat(ledger.hasFreshNewsEvidence(day, day)).isFalse();
        var live = new EvidenceLedger();
        live.importArtifact("旧消息 [E1]", List.of(Map.of("id", "E1", "source_key", "old:artifact", "location", Map.of(
                "published_at", now.toString(), "fetched_at", now.minusSeconds(3600).toString(),
                "evidence_kind", "live-news-search-summary"))));
        assertThat(live.hasFreshNewsEvidence(day, day)).isFalse();
    }

    @Test void supervisorCannotCompleteNewsWithNoNewDatedEvidenceButCanUseLiveEvidence() {
        var artifacts = mock(AgentArtifactStore.class);
        when(artifacts.forRun(1, 7, "run")).thenReturn(List.of());
        var validator = new AgentOutputValidator(json, artifacts);
        var ledger = new EvidenceLedger();
        var context = new AgentExecutionContext("run", "session", "supervisor", 1, 7,
                new AgentBudget(), ledger, null, null, ignored -> { });
        var supervisor = new AgentProfile("supervisor", "音频主控", "", Set.of(), "", 3);
        assertThatThrownBy(() -> validator.validate(supervisor, "今天的时政新闻", "新闻已经整理完成", Set.of(), context))
                .hasMessageContaining("本次联网");
        ledger.register(List.of(hit("news", Instant.now(), "live-news-search-summary")));
        assertThatCode(() -> validator.validate(supervisor, "今天的时政新闻", "这是来源支持的报道摘要 [E1]", Set.of(), context))
                .doesNotThrowAnyException();
    }

    private SearchHit hit(String id, Instant fetched, String kind) {
        return new SearchHit(id, "Publisher", "Reported summary", "https://publisher.test/" + id, .5,
                json.createObjectNode().put("published_at", now.toString()).put("fetched_at", fetched.toString())
                        .put("evidence_kind", kind));
    }
}
