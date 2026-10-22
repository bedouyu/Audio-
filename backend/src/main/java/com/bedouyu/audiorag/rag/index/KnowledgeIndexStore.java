package com.bedouyu.audiorag.rag.index;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.model.entity.KnowledgeIndexChunk;
import com.bedouyu.audiorag.model.entity.KnowledgeIndexDocument;
import com.bedouyu.audiorag.rag.strategy.TextSegment;
import com.bedouyu.audiorag.repository.KnowledgeIndexChunkRepository;
import com.bedouyu.audiorag.repository.KnowledgeIndexDocumentRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Complete, project-scoped canonical data for all retrieval strategies. */
@Service
@Transactional
public class KnowledgeIndexStore {
    public static final String INDEX_VERSION = "canonical-v2";
    public static final int MAX_DOCUMENTS_PER_PROJECT = 1000;
    public static final int MAX_CHUNKS_PER_PROJECT = 10000;

    private final KnowledgeIndexDocumentRepository documents;
    private final KnowledgeIndexChunkRepository chunks;

    public KnowledgeIndexStore(KnowledgeIndexDocumentRepository documents,
                               KnowledgeIndexChunkRepository chunks) {
        this.documents = documents;
        this.chunks = chunks;
    }

    public void save(long projectId, String docId, String title, String source, String type,
                     String version, String text, List<TextSegment> segments, List<String> pointIds) {
        requireUuid(docId);
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(segments, "segments");
        Objects.requireNonNull(pointIds, "pointIds");
        if (projectId < 1 || !Hashing.sha256(text).equals(version)) {
            throw new IllegalArgumentException("Project or canonical text version is invalid");
        }
        if (segments.isEmpty() || segments.size() != pointIds.size()
                || segments.size() > MAX_CHUNKS_PER_PROJECT) {
            throw new IllegalArgumentException("Canonical chunk and point ID counts are invalid");
        }
        if (new HashSet<>(pointIds).size() != pointIds.size()) {
            throw new IllegalArgumentException("Qdrant point IDs must be unique");
        }
        if (documents.existsById(docId)) throw new IllegalArgumentException("Document ID already exists");
        if (documents.countByProjectId(projectId) >= MAX_DOCUMENTS_PER_PROJECT
                || chunks.countByProjectId(projectId) + segments.size() > MAX_CHUNKS_PER_PROJECT)
            throw new IllegalStateException("Project exceeds local canonical index capacity; split the project before uploading");
        List<KnowledgeIndexChunk> rows = new ArrayList<>(segments.size());
        int previousStart = -1, previousEnd = 0;
        for (int index = 0; index < segments.size(); index++) {
            TextSegment segment = segments.get(index);
            requireUuid(pointIds.get(index));
            if (!docId.equals(segment.documentId()) || segment.ordinal() != index
                    || segment.start() < previousStart || segment.start() > previousEnd || segment.end() <= previousEnd
                    || segment.tokenCount() > 510 || segment.end() > text.length()
                    || !text.substring(segment.start(), segment.end()).equals(segment.text())) {
                throw new IllegalArgumentException("Chunk offsets must address the canonical document");
            }
            rows.add(new KnowledgeIndexChunk(pointIds.get(index), projectId, segment,
                    parentId(docId, text, segment.start()), title, source, type, version, INDEX_VERSION));
            previousStart = segment.start(); previousEnd = segment.end();
        }
        if (previousEnd != text.length()) throw new IllegalArgumentException("Canonical chunks must cover the complete text");
        documents.saveAndFlush(new KnowledgeIndexDocument(docId, projectId, title, source,
                type, version, INDEX_VERSION, text));
        // Both flushes are in one database transaction; partial canonical rows roll back together.
        chunks.saveAllAndFlush(rows);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeIndexDocument> documents(long projectId) {
        List<KnowledgeIndexDocument> rows = documents.findByProjectIdOrderByCreatedAtAscIdAsc(
                projectId, PageRequest.of(0, MAX_DOCUMENTS_PER_PROJECT + 1));
        if (rows.size() > MAX_DOCUMENTS_PER_PROJECT) {
            throw new IllegalStateException("Project exceeds canonical document limit "
                    + MAX_DOCUMENTS_PER_PROJECT + "; retrieval requires a paged index");
        }
        return List.copyOf(rows);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeIndexChunk> chunks(long projectId) {
        List<KnowledgeIndexChunk> rows = chunks.findByProjectIdOrderByDocumentIdAscOrdinalAsc(
                projectId, PageRequest.of(0, MAX_CHUNKS_PER_PROJECT + 1));
        if (rows.size() > MAX_CHUNKS_PER_PROJECT) {
            throw new IllegalStateException("Project exceeds canonical chunk limit "
                    + MAX_CHUNKS_PER_PROJECT + "; retrieval requires a paged index");
        }
        return List.copyOf(rows);
    }

    public void deleteDocument(long projectId, String docId) {
        chunks.deleteByProjectIdAndDocumentId(projectId, docId);
        documents.deleteByProjectIdAndId(projectId, docId);
    }

    public void deleteProject(long projectId) {
        chunks.deleteByProjectId(projectId);
        documents.deleteByProjectId(projectId);
    }

    private static void requireUuid(String value) {
        if (value == null || value.length() != 36 || !UUID.fromString(value).toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Document and point IDs must be UUIDs");
        }
    }

    /** A paragraph anchor, not an inferred semantic chapter. Offsets use normalized UTF-16 text. */
    private static String parentId(String docId, String text, int start) {
        int paragraph = text.lastIndexOf("\n\n", Math.max(0, start - 2));
        return docId + ":p:" + (paragraph < 0 ? 0 : paragraph + 2);
    }
}
