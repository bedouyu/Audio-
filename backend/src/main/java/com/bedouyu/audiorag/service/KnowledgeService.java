package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.tools.audio.TranscriptionService;

import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.rag.QdrantService;
import com.bedouyu.audiorag.rag.QdrantService.IngestionResult;
import com.bedouyu.audiorag.rag.OnnxEmbeddingService;
import com.bedouyu.audiorag.rag.index.KnowledgeIndexStore;
import com.bedouyu.audiorag.rag.strategy.SentenceTokenChunker;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.tools.storage.MinioStorageService;
import com.bedouyu.audiorag.tools.storage.MinioStorageService.StoredObject;
import com.bedouyu.audiorag.common.ApiException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.minio.GetObjectResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeService {
    private static final List<String> TEXT_EXTENSIONS = List.of("txt", "md", "srt", "vtt", "json", "jsonl", "csv");
    private final MinioStorageService minio;
    private final QdrantService qdrant;
    private final OnnxEmbeddingService embeddings;
    private final KnowledgeIndexStore index;
    private final TranscriptionService transcription;
    private final AudioRagRepository repository;
    private final ObjectMapper objectMapper;

    public KnowledgeService(MinioStorageService minio, QdrantService qdrant, OnnxEmbeddingService embeddings,
            KnowledgeIndexStore index, TranscriptionService transcription,
            AudioRagRepository repository, ObjectMapper objectMapper) {
        this.minio = minio;
        this.qdrant = qdrant;
        this.embeddings = embeddings; this.index = index;
        this.transcription = transcription;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> ingest(long userId, long projectId, String title, String filename,
            String contentType, byte[] bytes, String suppliedTranscript) {
        requireProject(userId, projectId);
        StoredObject stored = minio.put(projectId, filename, contentType, bytes);
        String docId = UUID.randomUUID().toString();
        List<String> pointIds = List.of();
        try {
            String text = extractText(filename, bytes, suppliedTranscript).replace("\r\n", "\n").strip();
            if (text.length() > 2_000_000)
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "文字超过本机结构化索引上限，请拆分文件");
            var chunks = new SentenceTokenChunker().split(docId, text, embeddings::contentTokenCount);
            if (chunks.isEmpty()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "文件中没有可入库的文字");
            }
            if (chunks.size() > KnowledgeIndexStore.MAX_CHUNKS_PER_PROJECT)
                throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "片段数超过本机索引上限");
            pointIds = chunks.stream().map(chunk -> UUID.randomUUID().toString()).toList();
            String version = Hashing.sha256(text);
            String type = suppliedTranscript != null && !suppliedTranscript.isBlank()
                    || !TEXT_EXTENSIONS.contains(extension(filename)) ? "transcript"
                    : switch (extension(filename)) {
                        case "srt", "vtt" -> "subtitle"; case "md" -> "markdown";
                        case "json", "jsonl", "csv" -> "structured_text"; default -> "text";
                    };
            IngestionResult ingestion = qdrant.upsertIndexed(projectId, title, stored.objectName(),
                    docId, version, type, chunks, pointIds);
            index.save(projectId, docId, title, stored.objectName(), type, version, text, chunks, pointIds);
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("chunks", ingestion.chunks()); metadata.put("point_ids", pointIds);
            metadata.put("bucket", stored.bucket()); metadata.put("object_name", stored.objectName());
            metadata.put("filename", stored.filename()); metadata.put("size", stored.size());
            metadata.put("document_id", docId); metadata.put("index_version", KnowledgeIndexStore.INDEX_VERSION);
            metadata.put("document_type", type); metadata.put("text_version", version);
            metadata.put("chunking_strategy", "sentence_384_48");
            long recordId = repository.addProjectRecord(projectId, "knowledge", title,
                    "已保存原文件并导入 " + ingestion.chunks() + " 个知识片段", metadata);
            return Map.of("id", recordId, "chunks", ingestion.chunks(), "title", title,
                    "filename", stored.filename());
        } catch (RuntimeException exception) {
            // 各存储独立补偿，保留原异常及清理失败信息；不宣称分布式事务。
            try { qdrant.deleteDocuments(pointIds); } catch (RuntimeException cleanup) { exception.addSuppressed(cleanup); }
            try { index.deleteDocument(projectId, docId); } catch (RuntimeException cleanup) { exception.addSuppressed(cleanup); }
            try { minio.remove(stored.objectName()); } catch (RuntimeException cleanup) { exception.addSuppressed(cleanup); }
            throw exception;
        }
    }

    public List<Map<String, Object>> list(long userId, long projectId) {
        return repository.knowledgeRecords(userId, projectId);
    }

    public Download download(long userId, long projectId, long recordId) {
        Map<String, Object> record = requiredRecord(userId, projectId, recordId);
        Map<String, Object> metadata = metadata(record);
        String objectName = String.valueOf(metadata.get("object_name"));
        String filename = String.valueOf(metadata.getOrDefault("filename", record.get("title")));
        GetObjectResponse stream = minio.get(objectName);
        return new Download(filename, stream);
    }

    public void delete(long userId, long projectId, long recordId) {
        Map<String, Object> record = requiredRecord(userId, projectId, recordId);
        Map<String, Object> metadata = metadata(record);
        List<String> pointIds = objectMapper.convertValue(metadata.getOrDefault("point_ids", List.of()),
                new TypeReference<>() {});
        qdrant.deleteDocuments(pointIds);
        minio.remove(String.valueOf(metadata.getOrDefault("object_name", "")));
        if (metadata.containsKey("document_id")) index.deleteDocument(projectId, String.valueOf(metadata.get("document_id")));
        repository.deleteProjectRecord(recordId);
    }

    public boolean ready() {
        return minio.ready();
    }

    private String extractText(String filename, byte[] bytes, String suppliedTranscript) {
        if (suppliedTranscript != null && !suppliedTranscript.isBlank()) {
            return suppliedTranscript;
        }
        String extension = extension(filename);
        if (TEXT_EXTENSIONS.contains(extension)) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return transcription.transcribe(filename, bytes);
    }

    private void requireProject(long userId, long projectId) {
        if (!repository.ownsProject(userId, projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "项目不存在或无访问权限");
        }
    }

    private Map<String, Object> requiredRecord(long userId, long projectId, long recordId) {
        return repository.knowledgeRecord(userId, projectId, recordId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "知识库文件不存在"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Map<String, Object> record) {
        Object value = record.get("metadata");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String extension(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public record Download(String filename, GetObjectResponse stream) {
    }
}
