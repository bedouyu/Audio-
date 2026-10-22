package com.bedouyu.audiorag.agent;

import com.bedouyu.audiorag.agent.audio.*;
import com.bedouyu.audiorag.ai.AgentModelGateway;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.*;
import com.bedouyu.audiorag.rag.*;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.tools.storage.MinioStorageService;
import com.bedouyu.audiorag.tools.research.WebSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.time.Instant;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.bedouyu.audiorag.repository.AgentRuntimeNodeRepository;
import com.bedouyu.audiorag.model.entity.AgentRuntimeNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:multi_agent;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "audiorag.llm.api-key="
})
@Import(MultiAgentWorkflowIntegrationTest.TestModels.class)
class MultiAgentWorkflowIntegrationTest {
    @TestConfiguration static class TestModels {
        @Bean @Primary ScenarioAgentGateway scenarioAgentGateway() { return new ScenarioAgentGateway(); }
    }
    @Autowired AudioRagRepository repository;
    @Autowired AgentOrchestrator orchestrator;
    @Autowired AgentArtifactStore artifacts;
    @Autowired AgentRunStore runs;
    @Autowired AgentArtifactApprovalService approval;
    @Autowired LearningPlanService learning;
    @Autowired ScenarioAgentGateway model;
    @Autowired ObjectMapper json;
    @Autowired AgentRuntimeNodeRepository nodes;
    @PersistenceContext EntityManager entities;
    @Autowired PlatformTransactionManager transactions;
    @Value("${local.server.port}") int port;
    @MockitoBean QdrantService qdrant;
    @MockitoBean OnnxEmbeddingService embeddings;
    @MockitoBean MinioStorageService minio;
    @MockitoBean SpeechService speech;
    @MockitoBean WebSearchService web;
    @MockitoBean ContinuousAudioService continuous;

    @Test
    @SuppressWarnings("unchecked")
    void supervisorDelegatesIndependentLoopsAndRequiresConfirmationBeforeRealActions() throws Exception {
        model.requests.clear();
        var fixture = fixture();
        String session = repository.openSession(fixture.user, fixture.project, null);
        repository.appendTurn(session, "私有旧问题", "私有旧回答");
        List<String> tokens = new ArrayList<>();
        Map<String, Object> result;
        try { result = orchestrator.chat(fixture.user,
                new AudioAgent.ChatCommand("根据资料，生成一份概率论课程讲稿并复核", session, fixture.project),
                tokens::add, ignored -> { }); }
        catch (RuntimeException error) {
            System.out.println("FAILED_AGENT_TRACE=" + json.writeValueAsString(repository.events(session)));
            throw error;
        }
        assertThat(tokens).isNotEmpty();
        assertThat(result.get("status")).isEqualTo("AWAITING_CONFIRMATION");
        var outputs = (List<Map<String, Object>>) result.get("artifacts");
        assertThat(outputs).extracting(a -> a.get("kind")).containsExactlyInAnyOrder(
                "LEARNING_PLAN", "RESEARCH_REPORT", "SCRIPT_DRAFT", "REVIEW_REPORT");
        assertThat((Map<String, Object>) result.get("stats")).containsEntry("delegations", 4);
        // 服务端解析出的语言/时长提示必须真的送到模型，而不是只存在于代码里。
        assertThat(json.writeValueAsString(model.requests)).contains("服务端确定性解析");
        model.requests.stream().filter(request -> !String.valueOf(request.get(0).get("content")).contains("AGENT_ID=supervisor"))
                .forEach(request -> assertThat(json.writeValueAsString(request)).doesNotContain("私有旧问题", "私有旧回答"));
        verify(speech, never()).synthesize(anyString(), anyString(), any());
        var draft = outputs.stream().filter(a -> "SCRIPT_DRAFT".equals(a.get("kind"))).findFirst().orElseThrow();
        var plan = outputs.stream().filter(a -> "LEARNING_PLAN".equals(a.get("kind"))).findFirst().orElseThrow();
        String draftId = String.valueOf(draft.get("id"));
        var rejected = post(fixture, draftId, "synthesize", false);
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(400);
        when(speech.synthesize(anyString(), anyString(), any())).thenAnswer(call -> "/media/" + call.getArgument(0) + ".wav");
        assertThat(post(fixture, draftId, "synthesize", true).statusCode()).isEqualTo(200);
        assertThat(post(fixture, draftId, "synthesize", true).statusCode()).isEqualTo(200);
        verify(speech, times(1)).synthesize(anyString(), anyString(), any());
        assertThat(artifacts.get(fixture.user, fixture.project, draftId).get("status")).isEqualTo("READY");
        approval.applyPlan(fixture.user, fixture.project, String.valueOf(plan.get("id")), true, String.valueOf(plan.get("content_hash")));
        approval.applyPlan(fixture.user, fixture.project, String.valueOf(plan.get("id")), true, String.valueOf(plan.get("content_hash")));
        assertThat(learning.list(fixture.project)).hasSize(3);
        assertThat(runs.get(fixture.user, fixture.project, String.valueOf(result.get("run_id"))).get("status"))
                .isEqualTo("SUCCEEDED");
        long otherProject = ((Number) repository.ensureProject(fixture.user, "另一主题").get("id")).longValue();
        assertThatThrownBy(() -> artifacts.get(fixture.user, otherProject, draftId)).hasMessageContaining("不属于");
        assertThat(runs.get(fixture.user, fixture.project, String.valueOf(result.get("run_id"))).get("trace")).asList().isNotEmpty();
    }
    @Test
    void restartedRunningTasksAndSynthesisAreMarkedForReviewWithoutReplay() {
        var fixture = fixture();
        String session = repository.openSession(fixture.user, fixture.project, null);
        String run = runs.start(fixture.user, fixture.project, session, "中断测试");
        var draft = artifacts.save(run, fixture.user, fixture.project, "script_writer", "SCRIPT_DRAFT",
                "一份待确认的课程讲稿，进程重启后不应该自行再次合成音频。", List.of(), List.of());
        String id = String.valueOf(draft.get("id"));
        assertThatThrownBy(() -> artifacts.beginSynthesis(fixture.user, fixture.project, id,
                String.valueOf(draft.get("content_hash")), null, true, "本机手工确认，跳过自动复核仅用于状态测试"))
                .hasMessageContaining("运行");
        runs.finish(run, "AWAITING_CONFIRMATION", "草稿已生成", Map.of());
        artifacts.beginSynthesis(fixture.user, fixture.project, id, String.valueOf(draft.get("content_hash")), null, true,
                "本机手工确认，跳过自动复核仅用于状态测试");
        String other = runs.start(fixture.user, fixture.project, session, "另一中断任务");
        String oldOwner = UUID.randomUUID().toString();
        nodes.saveAndFlush(new AgentRuntimeNode(oldOwner, Instant.now().minusSeconds(100), Instant.now().minusSeconds(1)));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> entities.createQuery(
                "update AgentRunRecord run set run.ownerId = :owner where run.id = :id")
                .setParameter("owner", oldOwner).setParameter("id", other).executeUpdate());
        runs.markInterrupted(); artifacts.markInterruptedSynthesis();
        assertThat(runs.get(fixture.user, fixture.project, other).get("status")).isEqualTo("INTERRUPTED");
        assertThat(artifacts.get(fixture.user, fixture.project, id).get("status")).isEqualTo("NEEDS_REVIEW");
        verify(speech, never()).synthesize(anyString(), anyString(), any());
    }
    @Test
    void ordinaryQuestionCanFinishWithoutInvokingAllSpecialists() {
        var fixture = fixture();
        var result = orchestrator.chat(fixture.user, new AudioAgent.ChatCommand("解释概率", null, fixture.project),
                ignored -> { }, ignored -> { });
        assertThat(result.get("status")).isEqualTo("SUCCEEDED");
        assertThat(((Map<?, ?>) result.get("stats")).get("delegations")).isEqualTo(0);
        assertThat(result.get("artifacts")).asList().isEmpty();
    }
    @Test
    void continuationReceivesSameSessionFailedTaskButNotOtherConversation() {
        var fixture = fixture();
        String session = repository.openSession(fixture.user, fixture.project, null);
        String failed = runs.start(fixture.user, fixture.project, session, "整理尚未完成的课程任务");
        runs.finish(failed, "FAILED", "测试中断，产物已保留", Map.of());
        String otherSession = repository.openSession(fixture.user, fixture.project, null);
        String other = runs.start(fixture.user, fixture.project, otherSession, "另一对话的私有任务");
        runs.finish(other, "FAILED", "其他任务", Map.of());
        model.requests.clear();
        orchestrator.chat(fixture.user, new AudioAgent.ChatCommand("继续", session, fixture.project),
                ignored -> { }, ignored -> { });
        String input = json.writeValueAsString(model.requests.get(0));
        assertThat(input).contains("整理尚未完成的课程任务", "FAILED").doesNotContain("另一对话的私有任务");
    }
    @Test
    void realChatStreamUsesNativeSupervisorAndReturnsPersistedRunId() throws Exception {
        var fixture = fixture();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/chat/stream"))
                .header("Authorization", "Bearer " + fixture.token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("project_id", fixture.project, "message", "解释概率")))).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("model_start", "knowledge_search", "\"type\":\"TOKEN\"", "\"type\":\"DONE\"");
        String frame = response.body().lines().filter(line -> line.contains("\"type\":\"RESULT\"")).findFirst().orElseThrow();
        String run = json.readTree(frame.substring(frame.indexOf("data:") + 5).strip()).path("data").path("run_id").asText();
        assertThat(runs.get(fixture.user, fixture.project, run).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test
    void httpRetryWithoutSessionReplaysSameRunWithoutCallingModelAgain() throws Exception {
        var fixture = fixture();
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("project_id", fixture.project,
                "message", "解释概率", "request_id", UUID.randomUUID().toString()));
        var first = chat(fixture, payload);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        int calls = model.requests.size();
        var again = chat(fixture, payload);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        var original = json.readTree(first.body()); var replay = json.readTree(again.body());
        assertThat(replay.path("run_id").asText()).isEqualTo(original.path("run_id").asText());
        assertThat(replay.path("session_id").asText()).isEqualTo(original.path("session_id").asText());
        assertThat(model.requests).hasSize(calls);
        for (String field : List.of("message", "memory_strategy", "required_artifacts")) {
            var changed = new LinkedHashMap<>(payload);
            changed.put(field, switch (field) {
                case "message" -> "不同问题";
                case "memory_strategy" -> "hybrid";
                default -> List.of("SCRIPT_DRAFT");
            });
            var rejected = chat(fixture, changed);
            assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);
        }
        assertThat(model.requests).hasSize(calls);
    }

    @Test
    void httpRetryRefreshesConfirmedArtifactStateAndKeepsTaskSpecification() throws Exception {
        var fixture = fixture();
        var payload = Map.<String, Object>of("project_id", fixture.project, "message", "生成课程讲稿并复核",
                "request_id", UUID.randomUUID().toString(), "required_artifacts", List.of("LEARNING_PLAN"));
        var first = chat(fixture, payload);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        var result = json.readTree(first.body());
        String run = result.path("run_id").asText();
        assertThat(json.valueToTree(runs.get(fixture.user, fixture.project, run))
                .path("task_specification").path("required_artifacts").toString())
                .contains("LEARNING_PLAN", "SCRIPT_DRAFT", "REVIEW_REPORT");
        int calls = model.requests.size();
        var outputs = artifacts.forRun(fixture.user, fixture.project, run);
        var plan = outputs.stream().filter(a -> "LEARNING_PLAN".equals(a.get("kind"))).findFirst().orElseThrow();
        var draft = outputs.stream().filter(a -> "SCRIPT_DRAFT".equals(a.get("kind"))).findFirst().orElseThrow();
        assertThat(post(fixture, String.valueOf(plan.get("id")), "apply-plan", true).statusCode()).isEqualTo(200);
        when(speech.synthesize(anyString(), anyString(), any())).thenAnswer(call -> "/media/" + call.getArgument(0) + ".wav");
        assertThat(post(fixture, String.valueOf(draft.get("id")), "synthesize", true).statusCode()).isEqualTo(200);
        var again = chat(fixture, payload);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        var replay = json.readTree(again.body());
        assertThat(replay.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(replay.path("artifacts").toString()).contains("READY", "APPLIED");
        assertThat(model.requests).hasSize(calls);
        verify(speech, times(1)).synthesize(anyString(), anyString(), any());
    }

    @Test
    void malformedApprovalJsonIsAClientErrorAndNeverExecutes() throws Exception {
        var fixture = fixture();
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/projects/" + fixture.project + "/agent/artifacts/" + UUID.randomUUID() + "/synthesize"))
                .header("Authorization", "Bearer " + fixture.token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"voice\":\"test\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(response.body()).doesNotContain("DeserializationFeature", "boolean");
        verify(speech, never()).synthesize(anyString(), anyString(), any());
    }

    private HttpResponse<String> chat(Fixture fixture, Map<String, Object> payload) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/chat"))
                .header("Authorization", "Bearer " + fixture.token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private Fixture fixture() {
        long user = ((Number) repository.createUser("u" + UUID.randomUUID().toString().substring(0, 12), "password").get("id")).longValue();
        long project = ((Number) repository.ensureProject(user, "概率论").get("id")).longValue();
        when(qdrant.search(anyString(), anyInt(), eq(project))).thenReturn(List.of(
                new QdrantService.SearchHit("point", "概率论笔记", "概率论研究随机事件的规律。", "projects/" + project + "/notes", .95, json.createObjectNode())));
        return new Fixture(user, project, repository.issueToken(user));
    }
    private HttpResponse<String> post(Fixture fixture, String id, String action, boolean confirmed) throws Exception {
        var artifact = artifacts.get(fixture.user, fixture.project, id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("confirmed", confirmed); payload.put("voice", "test");
        payload.put("expected_content_hash", artifact.get("content_hash"));
        if ("synthesize".equals(action)) artifacts.forRun(fixture.user, fixture.project, String.valueOf(artifact.get("run_id"))).stream()
                .filter(a -> "REVIEW_REPORT".equals(a.get("kind")))
                .filter(a -> id.equals(json.readTree(String.valueOf(a.get("content"))).path("reviewed_artifact_id").asText()))
                .findFirst().ifPresent(review -> payload.put("review_id", review.get("id")));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/projects/" + fixture.project + "/agent/artifacts/" + id + "/" + action))
                .header("Authorization", "Bearer " + fixture.token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private record Fixture(long user, long project, String token) { }
}
