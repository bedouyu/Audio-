package com.bedouyu.audiorag.rag;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.function.Supplier;
import com.bedouyu.audiorag.rag.strategy.TextSegment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class QdrantService {
    private final RestClient client;
    private final String collection;
    private final OnnxEmbeddingService embeddings;

    public QdrantService(RestClient.Builder builder, OnnxEmbeddingService embeddings,
            @Value("${audiorag.qdrant.host}") String host,
            @Value("${audiorag.qdrant.port}") int port,
            @Value("${audiorag.qdrant.collection}") String collection) {
        this.client = builder.baseUrl("http://" + host + ":" + port).build();
        this.collection = collection;
        this.embeddings = embeddings;
    }

    public List<SearchHit> search(String query, int limit, long projectId) {
        return searchFiltered(query, limit, List.of(
                Map.of("key", "project_id", "match", Map.of("value", projectId))));
    }

    /** 所有检索策略共享同一 collection；按项目与索引版本过滤，不创建策略专用数据库。 */
    public List<SearchHit> searchIndexed(String query, int limit, long projectId, List<String> documentIds) {
        List<Map<String, Object>> conditions = new ArrayList<>();
        conditions.add(Map.of("key", "project_id", "match", Map.of("value", projectId)));
        conditions.add(Map.of("key", "index_version", "match", Map.of("value", "canonical-v2")));
        if (!documentIds.isEmpty())
            conditions.add(Map.of("key", "document_id", "match", Map.of("any", documentIds)));
        return searchFiltered(query, limit, conditions);
    }

    private List<SearchHit> searchFiltered(String query, int limit, List<Map<String, Object>> conditions) {
        float[] vector = embed(query);
        validateDimension(vector, collectionDimension());
        JsonNode response = request(() -> client.post()
                .uri("/collections/{collection}/points/search", collection)
                .body(Map.of("vector", vector, "limit", limit,
                        "with_payload", true, "with_vector", false,
                        "filter", Map.of("must", conditions)))
                .retrieve()
                .body(JsonNode.class));
        List<SearchHit> hits = new ArrayList<>();
        if (response == null || !response.path("result").isArray()) {
            throw new VectorRetrievalException(VectorRetrievalException.Kind.PROTOCOL,
                    "向量服务返回的检索结果格式无效");
        }
        for (JsonNode result : response.path("result")) {
            JsonNode payload = result.path("payload");
            String content = payload.path("content").asText(payload.path("question").asText(""));
            String title = payload.path("title").asText(payload.path("question").asText("知识库资料"));
            String source = payload.path("source_object").asText(payload.path("source").asText("Qdrant"));
            hits.add(new SearchHit(result.path("id").asText(), title, content, source,
                    result.path("score").asDouble(), payload));
        }
        return hits;
    }

    public boolean ready() {
        try {
            JsonNode response = client.get().uri("/collections/{collection}", collection)
                    .retrieve().body(JsonNode.class);
            return response != null && "green".equals(response.path("result").path("status").asText());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public IngestionResult addDocuments(long projectId, String title, String source, List<String> chunks) {
        List<Map<String, Object>> points = new ArrayList<>();
        List<String> pointIds = new ArrayList<>();
        Integer dimension = null;
        for (int index = 0; index < chunks.size(); index++) {
            String content = chunks.get(index);
            String pointId = UUID.randomUUID().toString();
            pointIds.add(pointId);
            float[] vector = embed(content);
            if (dimension == null) dimension = collectionDimension();
            validateDimension(vector, dimension);
            points.add(Map.of(
                    "id", pointId,
                    "vector", vector,
                    "payload", Map.of("title", title, "source", source, "content", content,
                            "chunk_index", index, "project_id", projectId)));
        }
        request(() -> client.put().uri("/collections/{collection}/points?wait=true", collection)
                .body(Map.of("points", points)).retrieve().toBodilessEntity());
        return new IngestionResult(points.size(), pointIds);
    }

    /** IDs 在调用前分配，上传响应丢失时调用者仍可尝试补偿删除；这不是跨库事务。 */
    public IngestionResult upsertIndexed(long projectId, String title, String source, String documentId,
            String textVersion, String documentType, List<TextSegment> segments, List<String> pointIds) {
        if (segments.size() != pointIds.size()) throw new IllegalArgumentException("片段和向量 ID 数量不一致");
        List<Map<String, Object>> points = new ArrayList<>();
        Integer dimension = null;
        for (int i = 0; i < segments.size(); i++) {
            TextSegment chunk = segments.get(i);
            if (!documentId.equals(chunk.documentId()) || chunk.tokenCount() > 510)
                throw new IllegalArgumentException("片段归属错误或超出模型编码窗口");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("project_id", projectId); payload.put("document_id", documentId);
            payload.put("text_version", textVersion); payload.put("index_version", "canonical-v2");
            payload.put("document_type", documentType); payload.put("chunk_index", chunk.ordinal());
            payload.put("start_offset", chunk.start()); payload.put("end_offset", chunk.end());
            payload.put("token_count", chunk.tokenCount()); payload.put("title", title);
            payload.put("source", source); payload.put("content", chunk.text());
            float[] vector = embed(chunk.text());
            if (dimension == null) dimension = collectionDimension();
            validateDimension(vector, dimension);
            payload.put("embedding_model", embeddings.modelName()); payload.put("embedding_dimension", vector.length);
            payload.put("chunking_strategy", "sentence_384_48");
            points.add(Map.of("id", pointIds.get(i), "vector", vector, "payload", payload));
        }
        // 分批控制请求大小；任意一批失败都由调用者用完整 pointIds 清理。
        for (int from = 0; from < points.size(); from += 64) {
            List<Map<String, Object>> batch = points.subList(from, Math.min(points.size(), from + 64));
            request(() -> client.put().uri("/collections/{collection}/points?wait=true", collection)
                    .body(Map.of("points", batch)).retrieve().toBodilessEntity());
        }
        return new IngestionResult(points.size(), List.copyOf(pointIds));
    }

    public void deleteDocuments(List<String> pointIds) {
        if (pointIds == null || pointIds.isEmpty()) {
            return;
        }
        request(() -> client.post().uri("/collections/{collection}/points/delete?wait=true", collection)
                .body(Map.of("points", pointIds)).retrieve().toBodilessEntity());
    }

    public void deleteProjectDocuments(long projectId) {
        request(() -> client.post().uri("/collections/{collection}/points/delete?wait=true", collection)
                .body(Map.of("filter", Map.of("must", List.of(
                        Map.of("key", "project_id", "match", Map.of("value", projectId))))))
                .retrieve().toBodilessEntity());
    }

    private float[] embed(String text) {
        final float[] vector;
        try {
            vector = embeddings.embed(text);
        } catch (RuntimeException failure) {
            throw new VectorRetrievalException(VectorRetrievalException.Kind.EMBEDDING,
                    "本地向量生成失败，请检查 embedding 模型及分词器配置", failure);
        }
        if (vector == null || vector.length == 0)
            throw new VectorRetrievalException(VectorRetrievalException.Kind.EMBEDDING, "本地模型返回空向量");
        for (float value : vector)
            if (!Float.isFinite(value))
                throw new VectorRetrievalException(VectorRetrievalException.Kind.EMBEDDING, "本地模型返回非有限向量");
        return vector;
    }

    /** Validate the existing collection; never rebuild it or rewrite existing vectors. */
    private int collectionDimension() {
        JsonNode response = request(() -> client.get().uri("/collections/{collection}", collection)
                .retrieve().body(JsonNode.class));
        JsonNode size = response == null ? null
                : response.path("result").path("config").path("params").path("vectors").path("size");
        if (size == null || !size.isIntegralNumber() || size.asInt() <= 0)
            throw new VectorRetrievalException(VectorRetrievalException.Kind.SCHEMA,
                    "向量 collection 配置无效或使用不支持的 named vectors，请检查既有 collection schema");
        return size.asInt();
    }

    private static void validateDimension(float[] vector, int dimension) {
        if (vector.length != dimension)
            throw new VectorRetrievalException(VectorRetrievalException.Kind.DIMENSION_MISMATCH,
                    "embedding 维度 " + vector.length + " 与既有 collection 维度 " + dimension
                            + " 不一致；请使用原模型或先制定显式迁移方案");
    }

    private static <T> T request(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            VectorRetrievalException.Kind kind = status >= 500 || status == 408
                    ? VectorRetrievalException.Kind.TRANSIENT
                    : status == 401 || status == 403 ? VectorRetrievalException.Kind.AUTHENTICATION
                    : status == 404 ? VectorRetrievalException.Kind.COLLECTION_MISSING
                    : status == 400 || status == 422 ? VectorRetrievalException.Kind.SCHEMA
                    : VectorRetrievalException.Kind.REQUEST_REJECTED;
            throw new VectorRetrievalException(kind, "向量服务请求失败（HTTP " + status + "，" + kind + "）", failure);
        } catch (ResourceAccessException failure) {
            throw new VectorRetrievalException(VectorRetrievalException.Kind.TRANSIENT,
                    "向量服务连接失败或超时", failure);
        } catch (RestClientException failure) {
            throw new VectorRetrievalException(VectorRetrievalException.Kind.PROTOCOL,
                    "向量服务响应协议无效", failure);
        }
    }

    public record SearchHit(String id, String title, String content, String source, double score, JsonNode payload) {
        public Map<String, Object> citation() {
            return Map.of("id", id, "title", title, "source", source, "score", score);
        }
    }

    public record IngestionResult(int chunks, List<String> pointIds) {
    }
}
