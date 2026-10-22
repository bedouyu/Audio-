package com.bedouyu.audiorag;

import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.LearningPlanService;
import com.bedouyu.audiorag.service.memory.ConversationMemory;
import com.bedouyu.audiorag.rag.QdrantService;
import com.bedouyu.audiorag.rag.OnnxEmbeddingService;
import com.bedouyu.audiorag.tools.storage.MinioStorageService;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.tools.research.WebSearchService;
import com.bedouyu.audiorag.service.ContinuousAudioService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:audio_workspace;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "audiorag.llm.api-key="
})
class AudioWorkspaceIntegrationTest {
    @Autowired AudioRagRepository repository;
    @Autowired ConversationMemory memory;
    @Autowired LearningPlanService learning;
    @Autowired ObjectMapper json;
    @Autowired com.bedouyu.audiorag.rag.index.KnowledgeIndexStore index;
    @Autowired com.bedouyu.audiorag.service.KnowledgeService knowledge;
    @Autowired com.bedouyu.audiorag.memory.MemoryContextService memoryContexts;
    @Autowired com.bedouyu.audiorag.memory.MemoryCheckpointStore memoryCheckpoints;
    @Value("${local.server.port}") int port;
    @MockitoBean QdrantService qdrant;
    @MockitoBean OnnxEmbeddingService embeddings;
    @MockitoBean MinioStorageService minio;
    @MockitoBean SpeechService speech;
    @MockitoBean WebSearchService web;
    @MockitoBean ContinuousAudioService continuous;

    @Test void singleAudioHttpReturnsQueuedStateAndForwardsIdempotencyKey() throws Exception {
        long user = user(), project = project(user, "单节任务"); String key = UUID.randomUUID().toString();
        when(continuous.startSingle(eq(user), any(), eq(key))).thenReturn(Map.of("queue_id", "queue", "mode", "SINGLE", "status", "GENERATING", "lessons", List.of()));
        var response = postJson(repository.issueToken(user), "/api/v1/lessons", Map.of("topic", "概率", "project_id", project,
                "duration_minutes", 5, "request_id", key));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("GENERATING");
        assertThat(json.readTree(response.body()).has("audio_url")).isFalse();
        verify(continuous).startSingle(eq(user), argThat(c -> c.projectId() == project && c.durationMinutes() == 5), eq(key));
        verify(speech, never()).synthesize(anyString(), anyString(), any());
    }
    @Test void continuousHttpForwardsRequestScopeAndDoesNotExecuteTtsInController() throws Exception {
        long user = user(), project = project(user, "连续任务"); String key = UUID.randomUUID().toString();
        when(continuous.start(eq(user), any())).thenReturn(Map.of("queue_id", "queue", "status", "READY", "lessons", List.of()));
        var response = postJson(repository.issueToken(user), "/api/v1/continuous-audio", Map.of("project_id", project,
                "course_minutes", 30, "segment_minutes", 5, "prefetch_size", 2, "request_id", key));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        verify(continuous).start(eq(user), argThat(c -> key.equals(c.requestId()) && c.projectId() == project && "CONTINUOUS".equals(c.mode())));
        verify(speech, never()).synthesize(anyString(), anyString(), any());
    }
    @Test void recoveryHttpBindsExplicitHashAttemptAndReason() throws Exception {
        long user = user(); String id = UUID.randomUUID().toString(), audio = UUID.randomUUID().toString();
        String hash = "a".repeat(64), reason = "已人工核查原合成结果，明确重置这次尝试";
        when(continuous.resetSegment(user, id, 1, true, hash, audio, reason)).thenReturn(Map.of("status", "READY"));
        var response = postJson(repository.issueToken(user), "/api/v1/continuous-audio/"+id+"/segments/1/reset",
                Map.of("confirmed", true, "expected_script_hash", hash, "expected_audio_id", audio, "reason", reason));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        verify(continuous).resetSegment(user,id,1,true,hash,audio,reason);
    }
    private HttpResponse<String> postJson(String token, String path, Map<String,Object> payload) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void persistsScopedSessionsAndBoundedMemoryUsingJpa() {
        long user = user();
        long project = project(user, "概率论");
        String session = repository.openSession(user, project, null);
        repository.appendMessage(session, "user", "什么是概率测度？");
        repository.appendMessage(session, "assistant", "满足可列可加性。");
        assertThat(memory.window(session)).hasSize(2);
        assertThat(repository.sessionMessages(user, project, session)).hasSize(2);
        String legacy = UUID.randomUUID().toString();
        repository.appendMessage(legacy, "user", "原版问题");
        repository.appendMessage(legacy, "assistant", "原版回答");
        repository.addProjectRecord(project, "chat", "旧对话", "原版回答", Map.of("session_id", legacy));
        assertThat(repository.sessions(user, project)).extracting(item -> item.get("id")).contains(legacy);
        assertThat(repository.sessionMessages(user, project, legacy)).hasSize(2);
        assertThatThrownBy(() -> repository.requireSession(user + 1, project, session))
                .hasMessageContaining("不属于");
        assertThatThrownBy(() -> repository.openSession(user, project(user, "另一主题"), session))
                .hasMessageContaining("不属于");
        var first = learning.reserveNext(project, "概率论");
        var next = learning.reserveNext(project, "概率论");
        assertThat(next.id()).isNotEqualTo(first.id());
        learning.release(first.id());
        assertThat(learning.reserveNext(project, "概率论").id()).isEqualTo(first.id());
    }

    @Test
    void realHttpStreamReturnsResultAndDoneAndCarriesPriorMessages() throws Exception {
        long user = user();
        long project = project(user, "概率论");
        String token = repository.issueToken(user);
        when(qdrant.search(anyString(), anyInt(), eq(project))).thenReturn(List.of(
                new QdrantService.SearchHit("hit", "概率论资料", "概率论研究随机事件的规律。",
                        "projects/" + project + "/notes.txt", 0.95, json.createObjectNode())));
        HttpClient client = HttpClient.newHttpClient();
        String first = request(client, token, project, null, "解释概率论");
        var resultLine = first.lines().filter(line -> line.contains("\"type\":\"RESULT\"")).findFirst().orElseThrow();
        String id = json.readTree(resultLine.substring(resultLine.indexOf("data:") + 5).strip())
                .path("data").path("session_id").asText();
        assertThat(first).contains("\"type\":\"DONE\"", "概率论资料");
        String second = request(client, token, project, id, "继续");
        assertThat(second).contains("history_messages", "2");
        assertThat(repository.sessionMessages(user, project, id)).hasSize(4);
        assertThat(memory.window(id)).hasSize(4);
    }

    @Test
    @SuppressWarnings("unchecked")
    void newIngestionPersistsSharedCoordinatesAndDeleteCleansCanonicalRows() {
        long user = user(), project = project(user, "新索引"), other = project(user, "另一个索引");
        byte[] bytes = "第一段代码。\n\n第二段知识。".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(minio.put(eq(project), eq("notes.md"), anyString(), any())).thenReturn(
                new MinioStorageService.StoredObject("test", "projects/" + project + "/notes.md", "notes.md", bytes.length));
        when(embeddings.contentTokenCount(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).length());
        when(qdrant.upsertIndexed(eq(project), anyString(), anyString(), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenAnswer(call -> new QdrantService.IngestionResult(((List<?>) call.getArgument(6)).size(), call.getArgument(7)));
        var result = knowledge.ingest(user, project, "笔记", "notes.md", "text/plain", bytes, "");
        assertThat(index.documents(project)).hasSize(1);
        assertThat(index.chunks(project)).isNotEmpty();
        assertThat(index.documents(other)).isEmpty();
        var doc = index.documents(project).get(0);
        assertThat(doc.getDocumentType()).isEqualTo("markdown");
        index.chunks(project).forEach(chunk -> assertThat(doc.getText().substring(chunk.getStart(), chunk.getEnd())).isEqualTo(chunk.getText()));
        knowledge.delete(user, project, ((Number) result.get("id")).longValue());
        assertThat(index.documents(project)).isEmpty(); assertThat(index.chunks(project)).isEmpty();
    }

    @Test
    void partialVectorWriteFailureUsesPreallocatedIdsAndKeepsOriginalError() {
        long user = user(), project = project(user, "补偿");
        when(minio.put(eq(project), eq("fail.txt"), anyString(), any())).thenReturn(
                new MinioStorageService.StoredObject("test", "fail-object", "fail.txt", 4));
        when(embeddings.contentTokenCount(anyString())).thenAnswer(call -> ((String) call.getArgument(0)).length());
        when(qdrant.upsertIndexed(eq(project), anyString(), anyString(), anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenThrow(new IllegalStateException("upsert-response-lost"));
        doThrow(new IllegalStateException("cleanup-failed")).when(qdrant).deleteDocuments(argThat(ids -> ids != null && !ids.isEmpty()));
        assertThatThrownBy(() -> knowledge.ingest(user, project, "失败", "fail.txt", "text/plain", new byte[]{65, 66}, ""))
                .hasMessage("upsert-response-lost").satisfies(error -> assertThat(error.getSuppressed()).hasSize(1));
        verify(minio).remove("fail-object");
        assertThat(index.documents(project)).isEmpty();
    }

    @Test
    void sharedTurnIdentityCheckpointAndMemoryReadRemainScopedToSession() {
        long user = user(), project = project(user, "记忆隔离");
        String a = repository.openSession(user, project, null), b = repository.openSession(user, project, null);
        repository.appendTurn(a, "我希望课程面向初学者，不能使用矩阵。", "确认要求。");
        repository.appendTurn(b, "我希望讲解量子力学。", "另一个目标。");
        var messages = repository.memoryMessages(user, project, a, 400);
        assertThat(messages).hasSize(2); assertThat(messages.get(0).getTurnId()).isNotBlank().isEqualTo(messages.get(1).getTurnId());
        var prepared = memoryContexts.prepare(user, project, a, "继续", "hybrid");
        assertThat(prepared.pack().fragments()).anyMatch(f -> f.text().contains("初学者"));
        assertThat(memoryCheckpoints.get(user, project, a)).isPresent();
        String source = "m" + messages.get(0).getId();
        assertThat(memoryContexts.read(user, project, a, source, 0, 100).get("text")).isEqualTo(messages.get(0).getContent());
        assertThatThrownBy(() -> memoryContexts.read(user, project, b, source, 0, 100)).hasMessageContaining("current session");
        assertThatThrownBy(() -> memoryContexts.prepare(user + 1, project, a, "x", "hybrid")).hasMessageContaining("权限");
        memoryCheckpoints.deleteProject(project); assertThat(memoryCheckpoints.get(user, project, a)).isEmpty();
        assertThat(repository.memoryMessages(user, project, a, 400)).hasSize(2);
    }

    private String request(HttpClient client, String token, long project, String id, String message) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("project_id", project); body.put("session_id", id); body.put("message", message);
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/chat/stream"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
    private long user() { return ((Number) repository.createUser("u" + UUID.randomUUID().toString().substring(0, 12), "password").get("id")).longValue(); }
    private long project(long user, String topic) { return ((Number) repository.ensureProject(user, topic).get("id")).longValue(); }
}
