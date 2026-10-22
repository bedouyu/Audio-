package com.bedouyu.audiorag.rag;

import com.bedouyu.audiorag.model.entity.KnowledgeIndexDocument;
import com.bedouyu.audiorag.rag.strategy.*;
import com.bedouyu.audiorag.rag.index.KnowledgeIndexStore;
import com.bedouyu.audiorag.rag.index.Bm25IndexCache;
import org.springframework.beans.factory.annotation.Autowired;
import com.bedouyu.audiorag.tools.research.KnowledgeSearchTool;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;

/** 同一套 canonical leaf 索引，查询时更换召回与证据组合方式，不为策略复制数据库。 */
@Service
public class AdaptiveKnowledgeRetriever {
    private static final int CANDIDATE_DEPTH = 50;
    private final KnowledgeIndexStore index;
    private final QdrantService qdrant;
    private final OnnxEmbeddingService embeddings;
    private final KnowledgeSearchTool legacy;
    private final ObjectMapper json;
    private final Bm25IndexCache lexicalCache;
    public AdaptiveKnowledgeRetriever(KnowledgeIndexStore index, QdrantService qdrant,
            OnnxEmbeddingService embeddings, KnowledgeSearchTool legacy, ObjectMapper json) {
        this(index, qdrant, embeddings, legacy, json, new Bm25IndexCache());
    }
    @Autowired
    public AdaptiveKnowledgeRetriever(KnowledgeIndexStore index, QdrantService qdrant,
            OnnxEmbeddingService embeddings, KnowledgeSearchTool legacy, ObjectMapper json, Bm25IndexCache lexicalCache) {
        this.index = index; this.qdrant = qdrant; this.embeddings = embeddings;
        this.legacy = legacy; this.json = json; this.lexicalCache = lexicalCache;
    }

    public Result search(long projectId, String query, String retrieval, String assembly,
                         int tokenBudget, int limit, List<String> documentIds) {
        requireChoice(retrieval, Set.of("dense", "bm25", "hybrid"));
        requireChoice(assembly, Set.of("top_k", "neighbors", "rse_rank_proxy"));
        if (tokenBudget < 256 || tokenBudget > 2048 || limit < 1 || limit > 6)
            throw new IllegalArgumentException("检索预算超限");
        List<String> warnings = new ArrayList<>();
        List<KnowledgeIndexDocument> allDocuments = index.documents(projectId);
        Set<String> selectedIds = new HashSet<>(documentIds);
        Set<String> available = allDocuments.stream().map(KnowledgeIndexDocument::getId).collect(Collectors.toSet());
        if (!available.containsAll(selectedIds)) throw new IllegalArgumentException("文档不属于当前项目或未建立新版索引");
        if (allDocuments.isEmpty()) {
            List<QdrantService.SearchHit> hits = new ArrayList<>(); int usedTokens = 0, usedCharacters = 0;
            List<QdrantService.SearchHit> oldHits;
            try {
                oldHits = legacy.execute(projectId, query, limit);
            } catch (VectorRetrievalException failure) {
                if (!failure.isTransient()) throw failure;
                throw new VectorRetrievalException(VectorRetrievalException.Kind.TRANSIENT,
                        "旧索引向量检索暂时不可用，当前项目没有新版 BM25 索引可供降级", failure);
            }
            for (var hit : oldHits) {
                int tokens = embeddings.contentTokenCount(ContextText.render(hit.id(), hit.content()));
                int charge = observationCharge(hit);
                if (usedTokens + tokens > tokenBudget || usedCharacters + charge > 5800) continue;
                hits.add(hit); usedTokens += tokens; usedCharacters += charge;
            }
            return new Result("legacy_dense", "legacy_top_k", 0, usedTokens, List.copyOf(hits),
                    List.of("当前只有旧索引：使用旧版向量检索，缺少原文位置，不能执行 BM25/邻居扩展/RSE。"));
        }
        Map<String, KnowledgeIndexDocument> documents = allDocuments.stream()
                .filter(doc -> selectedIds.isEmpty() || selectedIds.contains(doc.getId()))
                .collect(Collectors.toMap(KnowledgeIndexDocument::getId, doc -> doc));
        var rows = index.chunks(projectId).stream().filter(row -> documents.containsKey(row.getDocumentId())).toList();
        List<TextSegment> chunks = rows.stream().map(row -> row.toSegment()).toList();
        Map<String, TextSegment> byPoint = rows.stream().collect(Collectors.toMap(row -> row.getPointId(), row -> row.toSegment()));
        List<RankedChunk> dense = new ArrayList<>();
        List<QdrantService.SearchHit> legacyHits = new ArrayList<>();
        boolean denseFailed = false;
        // 即使 BM25 被选择，也为尚未重建的旧文档保留向量兼容分支，不静默遗漏。
        if (!"bm25".equals(retrieval) || selectedIds.isEmpty()) {
            try {
                var hits = selectedIds.isEmpty() ? qdrant.search(query, CANDIDATE_DEPTH, projectId)
                        : qdrant.searchIndexed(query, CANDIDATE_DEPTH, projectId, documentIds);
                for (var hit : hits) {
                    TextSegment chunk = byPoint.get(hit.id());
                    if (chunk != null) dense.add(new RankedChunk(chunk, hit.score(), dense.size()));
                    else if (selectedIds.isEmpty() && (hit.payload() == null || !KnowledgeIndexStore.INDEX_VERSION
                            .equals(hit.payload().path("index_version").asText()))) legacyHits.add(hit);
                }
            } catch (VectorRetrievalException unavailable) {
                if (!unavailable.isTransient()) throw unavailable;
                if (chunks.isEmpty())
                    throw new VectorRetrievalException(VectorRetrievalException.Kind.TRANSIENT,
                            "向量检索暂时不可用，当前选择没有新版 BM25 片段可供降级", unavailable);
                denseFailed = true;
                warnings.add("向量服务暂时不可用（连接、超时或服务端错误），已降级为当前项目新版片段的 BM25；旧文档未参与本次检索。");
            }
        }
        Bm25IndexCache.View lexicalIndex = "dense".equals(retrieval) && !denseFailed ? null : lexicalCache.acquire(projectId, chunks);
        List<RankedChunk> lexical = lexicalIndex == null ? List.of() : lexicalIndex.ranker().rank(query, CANDIDATE_DEPTH);
        String actualRetrieval = denseFailed ? "bm25_fallback" : retrieval;
        List<RankedChunk> candidates = denseFailed || "bm25".equals(retrieval) ? lexical
                : "dense".equals(retrieval) ? dense : HybridRanking.fuse(dense, lexical, CANDIDATE_DEPTH);
        SegmentAssembler assembler = switch (assembly) {
            case "neighbors" -> new NeighborWindowAssembler();
            case "rse_rank_proxy" -> new RseSegmentAssembler();
            default -> new TopKSegmentAssembler();
        };
        // assembler 需要完整相邻关系，不能先用旧 ContextSelector 按source裁剪。
        Map<String, String> originals = documents.values().stream()
                .collect(Collectors.toMap(KnowledgeIndexDocument::getId, KnowledgeIndexDocument::getText));
        var segments = assembler.assemble(originals, chunks, candidates, tokenBudget, embeddings::contentTokenCount);
        List<QdrantService.SearchHit> result = new ArrayList<>();
        int returnedTokens = 0, returnedChars = 0;
        int structuredLimit = !legacyHits.isEmpty() && limit > 1 ? limit - 1 : limit;
        for (EvidenceSegment segment : segments) {
            if (result.size() >= structuredLimit) break;
            var doc = documents.get(segment.documentId());
            var payload = json.valueToTree(Map.of("document_id", doc.getId(), "text_version", doc.getTextVersion(),
                    "index_version", doc.getIndexVersion(), "start_offset", segment.start(),
                    "end_offset", segment.end(), "chunk_ids", segment.chunkIds(), "document_type", doc.getDocumentType()));
            var hit = new QdrantService.SearchHit(doc.getId() + ":" + segment.start() + "-" + segment.end(),
                    doc.getTitle(), segment.text(), doc.getSource(), segment.utility(), payload);
            int charge = observationCharge(hit);
            if (segment.text().length() > 4800 || returnedChars + charge > 5800) {
                warnings.add("达到工具观察字符预算，省略完整证据段，未截断段内原文。"); continue;
            }
            result.add(hit); returnedTokens += segment.contextTokens(); returnedChars += charge;
        }
        if (!legacyHits.isEmpty()) {
            warnings.add("旧文档仍走独立 dense/Top-K 兼容分支，不具备新版词法索引或连续证据段。可重新上传以升级。");
            actualRetrieval += "+legacy_dense";
            // 分数不可比较，不能假装两类索引已经统一重排。保留一个兼容证据位置。
            for (var hit : legacyHits) {
                if (result.size() >= limit || returnedChars + observationCharge(hit) > 5800) break;
                int tokens = embeddings.contentTokenCount(ContextText.render(hit.id(), hit.content()));
                if (returnedTokens + tokens > tokenBudget) continue;
                result.add(hit); returnedTokens += tokens; returnedChars += observationCharge(hit); break;
            }
        }
        if (result.isEmpty() && !candidates.isEmpty()) warnings.add("候选存在，但完整证据段未能装入预算；可增加预算或更换上下文策略。");
        return new Result(actualRetrieval, assembler.id(), candidates.size(), returnedTokens,
                List.copyOf(result), warnings.stream().distinct().toList(),
                lexicalIndex == null ? "NOT_USED" : lexicalIndex.status(), lexicalIndex == null ? "" : lexicalIndex.revision());
    }
    private static void requireChoice(String choice, Set<String> allowed) {
        if (!allowed.contains(choice)) throw new IllegalArgumentException("不支持的检索策略：" + choice);
    }
    /** 在登记引用之前给JSON转义、标题、路径和位置字段预留空间，避免执行器截断半段JSON。 */
    private int observationCharge(QdrantService.SearchHit hit) {
        return json.writeValueAsString(Map.of("text", hit.content(), "title", hit.title(), "source", hit.source(),
                "location", hit.payload() == null ? Map.of() : hit.payload())).length() + 100;
    }
    public record Result(String retrieval, String assembly, int candidateCount, int contextTokens,
                         List<QdrantService.SearchHit> hits, List<String> warnings, String indexCacheStatus, String indexRevision) {
        public Result(String retrieval, String assembly, int candidateCount, int contextTokens,
                List<QdrantService.SearchHit> hits, List<String> warnings) {
            this(retrieval, assembly, candidateCount, contextTokens, hits, warnings, "NOT_USED", "");
        }
    }
}
