package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.service.ContinuousAudioService;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.rag.QdrantService;
import com.bedouyu.audiorag.rag.index.KnowledgeIndexStore;
import com.bedouyu.audiorag.memory.MemoryCheckpointStore;
import com.bedouyu.audiorag.tools.storage.MinioStorageService;
import com.bedouyu.audiorag.common.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProjectDeletionService {
    private final AudioRagRepository repository;
    private final QdrantService qdrant;
    private final MinioStorageService minio;
    private final ContinuousAudioService continuousAudio;
    private final SpeechService speech;
    private final AgentRunStore runs;
    private final AgentArtifactStore artifacts;
    private final KnowledgeIndexStore index;
    private final MemoryCheckpointStore memory;
    private final com.bedouyu.audiorag.repository.PlaybackProgressRepository playback;
    private final com.bedouyu.audiorag.repository.AudioInstructionReceiptRepository receipts;

    public ProjectDeletionService(AudioRagRepository repository, QdrantService qdrant,
            MinioStorageService minio, ContinuousAudioService continuousAudio, SpeechService speech,
            AgentRunStore runs, AgentArtifactStore artifacts, KnowledgeIndexStore index, MemoryCheckpointStore memory,
            com.bedouyu.audiorag.repository.PlaybackProgressRepository playback,
            com.bedouyu.audiorag.repository.AudioInstructionReceiptRepository receipts) {
        this.repository = repository;
        this.qdrant = qdrant;
        this.minio = minio;
        this.continuousAudio = continuousAudio;
        this.speech = speech;
        this.runs = runs; this.artifacts = artifacts;
        this.index = index;
        this.memory = memory;
        this.playback = playback; this.receipts = receipts;
    }

    public void delete(long userId, long projectId) {
        if (!repository.ownsProject(userId, projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "项目不存在或无访问权限");
        }
        List<Map<String, Object>> records = repository.projectRecords(userId, projectId);
        continuousAudio.clearProject(userId, projectId);
        for (Map<String, Object> record : records) {
            Map<String, Object> metadata = metadata(record);
            if ("knowledge".equals(record.get("record_type"))) {
                minio.remove(String.valueOf(metadata.getOrDefault("object_name", "")));
            }
            speech.remove(String.valueOf(metadata.getOrDefault("audio_url", "")));
        }
        qdrant.deleteProjectDocuments(projectId);
        index.deleteProject(projectId);
        memory.deleteProject(projectId);
        repository.deleteSessions(sessionIds(records));
        artifacts.deleteProject(projectId);
        runs.deleteProject(projectId);
        playback.deleteByUserIdAndProjectId(userId, projectId);
        receipts.deleteByUserIdAndProjectId(userId, projectId);
        repository.deleteProject(userId, projectId);
    }

    private List<String> sessionIds(List<Map<String, Object>> records) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> record : records) {
            Object sessionId = metadata(record).get("session_id");
            if (sessionId != null && !String.valueOf(sessionId).isBlank()) {
                ids.add(String.valueOf(sessionId));
            }
        }
        return ids.stream().distinct().toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metadata(Map<String, Object> record) {
        Object value = record.get("metadata");
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
