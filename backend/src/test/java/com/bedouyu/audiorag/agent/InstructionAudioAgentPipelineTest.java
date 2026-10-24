package com.bedouyu.audiorag.agent;

import com.bedouyu.audiorag.ai.AiModelGateway;
import com.bedouyu.audiorag.rag.*;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.*;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.tools.research.WebSearchService;
import com.bedouyu.audiorag.tools.storage.MinioStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

/** Covers the actual audio HTTP entry through orchestration, reviewed text, queue and synthesis dispatch. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:audio_agent_pipeline;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "audiorag.llm.api-key="
})
@Import(InstructionAudioAgentPipelineTest.Models.class)
class InstructionAudioAgentPipelineTest {
    @TestConfiguration static class Models {
        @Bean @Primary ScenarioAgentGateway scenarioAgentGateway() { return new ScenarioAgentGateway(); }
    }
    @Autowired AudioRagRepository repository;
    @Autowired ContinuousAudioStore queues;
    @Autowired AgentArtifactStore artifacts;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    @MockitoBean QdrantService qdrant;
    @MockitoBean OnnxEmbeddingService embeddings;
    @MockitoBean MinioStorageService minio;
    @MockitoBean SpeechService speech;
    @MockitoBean WebSearchService web;
    @MockitoBean StringRedisTemplate redis;
    @MockitoBean AiModelGateway singleShot;
    @TempDir Path directory;

    @Test void naturalAudioRequestUsesTheReviewedAgentDraftInsteadOfSingleShotGeneration() throws Exception {
        long user = ((Number) repository.createUser("pipeline_" + UUID.randomUUID().toString().substring(0, 8), "password").get("id")).longValue();
        long project = ((Number) repository.ensureProject(user, "概率论").get("id")).longValue();
        when(qdrant.search(anyString(), anyInt(), eq(project))).thenReturn(List.of(
                new QdrantService.SearchHit("point", "概率论笔记", "概率论研究随机事件的规律。",
                        "projects/" + project + "/notes", .95, json.createObjectNode())));
        Path wave = directory.resolve("fixture.wav");
        byte[] pcm = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46464952).putInt(38).putInt(0x45564157).putInt(0x20746d66).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(24000).putInt(48000)
                .putShort((short) 2).putShort((short) 16).putInt(0x61746164).putInt(2).putShort((short) 1).array();
        Files.write(wave, pcm);
        when(speech.audioFile(anyString())).thenReturn(wave);
        when(speech.synthesize(anyString(), anyString(), any())).thenAnswer(call -> "/media/" + call.getArgument(0) + ".wav");

        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/v1/audio/requests"))
                .header("Authorization", "Bearer " + repository.issueToken(user))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "instruction", "根据资料生成一份概率论讲稿并复核", "project_id", project,
                        "request_id", UUID.randomUUID().toString())))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
        String queueId = json.readTree(response.body()).path("queue_id").asText();
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            while (true) {
                var state = queues.status(user, queueId);
                assertThat(state.get("status")).as(state.toString()).isNotIn("FAILED", "NEEDS_REVIEW");
                if ("COMPLETE".equals(state.get("status"))) break;
                Thread.sleep(25);
            }
        });
        var outputs = artifacts.list(user, project);
        var draft = outputs.stream().filter(a -> "SCRIPT_DRAFT".equals(a.get("kind"))).findFirst().orElseThrow();
        var review = outputs.stream().filter(a -> "REVIEW_REPORT".equals(a.get("kind"))).findFirst().orElseThrow();
        assertThat(draft.get("status")).isEqualTo("CONSUMED");
        assertThat(json.readTree(String.valueOf(review.get("content"))).path("reviewed_artifact_id").asText()).isEqualTo(draft.get("id"));
        assertThat(json.readTree(String.valueOf(review.get("content"))).path("content_hash").asText()).isEqualTo(draft.get("content_hash"));
        assertThat(json.readTree(String.valueOf(review.get("content"))).path("approved").asBoolean()).isTrue();
        verify(speech).synthesize(anyString(), eq(String.valueOf(draft.get("content"))), any());
        verifyNoInteractions(singleShot);
    }
}
