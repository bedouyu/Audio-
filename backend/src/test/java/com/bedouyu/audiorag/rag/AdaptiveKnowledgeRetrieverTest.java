package com.bedouyu.audiorag.rag;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.model.entity.*;
import com.bedouyu.audiorag.rag.strategy.TextSegment;
import com.bedouyu.audiorag.rag.index.KnowledgeIndexStore;
import com.bedouyu.audiorag.tools.research.KnowledgeSearchTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdaptiveKnowledgeRetrieverTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeIndexStore store = mock(KnowledgeIndexStore.class);
    private final QdrantService qdrant = mock(QdrantService.class);
    private final OnnxEmbeddingService embeddings = mock(OnnxEmbeddingService.class);
    private final KnowledgeSearchTool legacy = mock(KnowledgeSearchTool.class);
    private final AdaptiveKnowledgeRetriever retrieval = new AdaptiveKnowledgeRetriever(store, qdrant, embeddings, legacy, json);

    private String fixture() {
        String id = UUID.randomUUID().toString(), text = "先验证权限。然后修改代码。最后检查结果。";
        String version = Hashing.sha256(text);
        when(store.documents(7)).thenReturn(List.of(new KnowledgeIndexDocument(id, 7, "代码流程", "local.md",
                "markdown", version, "canonical-v2", text)));
        List<KnowledgeIndexChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int start = i == 0 ? 0 : i == 1 ? 6 : 13, end = i == 0 ? 6 : i == 1 ? 13 : text.length();
            chunks.add(new KnowledgeIndexChunk("point" + i, 7,
                    new TextSegment(id, i, start, end, text.substring(start, end), end - start),
                    id + ":p:0", "代码流程", "local.md", "markdown", version, "canonical-v2"));
        }
        when(store.chunks(7)).thenReturn(chunks);
        when(embeddings.contentTokenCount(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).length());
        return id;
    }
    @Test void legacyFallbackIsExplicitAndNeverPretendsToRunRse() {
        when(store.documents(7)).thenReturn(List.of());
        when(legacy.execute(7, "question", 4)).thenReturn(List.of());
        var result = retrieval.search(7, "question", "hybrid", "rse_rank_proxy", 1024, 4, List.of());
        assertThat(result.assembly()).isEqualTo("legacy_top_k");
        assertThat(result.warnings()).anyMatch(w -> w.contains("旧索引"));
        verify(qdrant, never()).searchIndexed(anyString(), anyInt(), anyLong(), anyList());
    }
    @Test void bm25AndNeighborContextShareCanonicalSourceAndRespectDocumentScope() {
        String id = fixture();
        var result = retrieval.search(7, "修改代码", "bm25", "neighbors", 512, 4, List.of(id));
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).content()).isEqualTo("先验证权限。然后修改代码。最后检查结果。");
        assertThat(result.contextTokens()).isLessThanOrEqualTo(512);
        verifyNoInteractions(qdrant);
    }
    @Test void qdrantFailureFallsBackToLexicalRecallAndDoesNotLeakAnotherProject() {
        fixture();
        when(qdrant.search(anyString(), anyInt(), eq(7L))).thenThrow(
                new VectorRetrievalException(VectorRetrievalException.Kind.TRANSIENT, "offline"));
        var result = retrieval.search(7, "修改代码", "hybrid", "rse_rank_proxy", 512, 4, List.of());
        assertThat(result.retrieval()).isEqualTo("bm25_fallback");
        assertThat(result.hits()).isNotEmpty();
        assertThat(result.warnings()).anyMatch(w -> w.contains("降级"));
        assertThatThrownBy(() -> retrieval.search(7, "x", "bm25", "top_k", 512, 4, List.of("other")))
                .hasMessageContaining("不属于");
    }
    @ParameterizedTest
    @EnumSource(value = VectorRetrievalException.Kind.class, mode = EnumSource.Mode.EXCLUDE, names = "TRANSIENT")
    void permanentVectorFailuresRemainExplicit(VectorRetrievalException.Kind kind) {
        fixture();
        var failure = new VectorRetrievalException(kind, "configuration or embedding failure");
        when(qdrant.search(anyString(), anyInt(), eq(7L))).thenThrow(failure);
        assertThatThrownBy(() -> retrieval.search(7, "修改代码", "hybrid", "top_k", 512, 4, List.of()))
                .isSameAs(failure);
    }
    @Test void unexpectedRuntimeFailureIsNeverTreatedAsOffline() {
        fixture();
        var failure = new IllegalStateException("unexpected bug");
        when(qdrant.search(anyString(), anyInt(), eq(7L))).thenThrow(failure);
        assertThatThrownBy(() -> retrieval.search(7, "修改代码", "hybrid", "top_k", 512, 4, List.of()))
                .isSameAs(failure);
    }
    @Test void legacyOnlyOfflineCannotPretendToUseBm25() {
        when(store.documents(7)).thenReturn(List.of());
        when(legacy.execute(7, "question", 4)).thenThrow(
                new VectorRetrievalException(VectorRetrievalException.Kind.TRANSIENT, "offline"));
        assertThatThrownBy(() -> retrieval.search(7, "question", "bm25", "neighbors", 512, 4, List.of()))
                .isInstanceOf(VectorRetrievalException.class).hasMessageContaining("没有新版 BM25");
        verifyNoInteractions(qdrant);
    }
    @Test void emptyCanonicalChunksCannotPretendToProvideFallback() {
        fixture();
        when(store.chunks(7)).thenReturn(List.of());
        when(qdrant.search(anyString(), anyInt(), eq(7L))).thenThrow(
                new VectorRetrievalException(VectorRetrievalException.Kind.TRANSIENT, "offline"));
        assertThatThrownBy(() -> retrieval.search(7, "question", "hybrid", "top_k", 512, 4, List.of()))
                .isInstanceOf(VectorRetrievalException.class).hasMessageContaining("没有新版 BM25");
    }
    @Test void projectWideBm25KeepsLegacyDenseSeparateAndWarnsAboutIncomparableScores() {
        fixture();
        var oldHit = new QdrantService.SearchHit("old", "旧资料", "这是旧资料。", "old.md", 0.99, json.createObjectNode());
        when(qdrant.search("修改代码", 50, 7)).thenReturn(List.of(oldHit));
        var result = retrieval.search(7, "修改代码", "bm25", "top_k", 512, 4, List.of());
        assertThat(result.retrieval()).isEqualTo("bm25+legacy_dense");
        assertThat(result.hits()).anyMatch(hit -> hit.id().equals("old"));
        assertThat(result.warnings()).anyMatch(w -> w.contains("独立 dense/Top-K"));
        assertThat(result.hits().get(0).id()).isNotEqualTo("old");
    }
    @Test void strategyAndBudgetsCannotBeExpandedByTheModel() {
        assertThatThrownBy(() -> retrieval.search(7, "x", "shell", "top_k", 512, 4, List.of())).hasMessageContaining("不支持");
        assertThatThrownBy(() -> retrieval.search(7, "x", "hybrid", "top_k", 999999, 4, List.of())).hasMessageContaining("预算");
    }
}
