package com.bedouyu.audiorag.agent;

import com.bedouyu.audiorag.rag.OnnxEmbeddingService;
import com.bedouyu.audiorag.rag.QdrantService;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.AgentArtifactStore;
import com.bedouyu.audiorag.service.AgentRunStore;
import com.bedouyu.audiorag.service.ContinuousAudioService;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.tools.research.WebSearchService;
import com.bedouyu.audiorag.tools.storage.MinioStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 音频队列直接消费讲稿时，草稿不能继续停留在“待确认”。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:artifact_consume;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "audiorag.llm.api-key="
})
class AgentArtifactConsumeTest {
    @Autowired AudioRagRepository repository;
    @Autowired AgentArtifactStore artifacts;
    @Autowired AgentRunStore runs;
    @MockitoBean QdrantService qdrant;
    @MockitoBean OnnxEmbeddingService embeddings;
    @MockitoBean MinioStorageService minio;
    @MockitoBean SpeechService speech;
    @MockitoBean WebSearchService web;
    @MockitoBean ContinuousAudioService continuous;

    @Test void consumingADraftRetiresItAndSettlesTheRun() {
        var fixture = fixture();
        var draft = artifacts.save(fixture.run, fixture.user, fixture.project, "script_writer",
                "SCRIPT_DRAFT", "小熊第一次独自走进森林，月亮跟着它回家。", List.of(), List.of());
        String id = String.valueOf(draft.get("id"));
        // 产物只能在运行中写入；写完后任务才进入“等待确认”。
        runs.finish(fixture.run, "AWAITING_CONFIRMATION", "等待确认讲稿", Map.of());

        var consumed = artifacts.consume(fixture.user, fixture.project, id);

        assertThat(consumed.get("status")).isEqualTo("CONSUMED");
        assertThat(artifacts.get(fixture.user, fixture.project, id).get("status")).isEqualTo("CONSUMED");
        // 消费后该任务不再有待确认产物，run 收敛为成功。
        assertThat(runs.get(fixture.user, fixture.project, fixture.run).get("status")).isEqualTo("SUCCEEDED");
        // 重复消费幂等。
        assertThat(artifacts.consume(fixture.user, fixture.project, id).get("status")).isEqualTo("CONSUMED");
        // 已消费的讲稿不能再走人工确认合成。
        assertThatThrownBy(() -> artifacts.beginSynthesis(fixture.user, fixture.project, id))
                .hasMessageContaining("已被直接生成的音频消费");
    }

    @Test void onlyAScriptDraftCanBeConsumedByTheAudioQueue() {
        var fixture = fixture();
        var plan = artifacts.save(fixture.run, fixture.user, fixture.project, "study_planner", "LEARNING_PLAN",
                "{\"points\":[{\"title\":\"一\"},{\"title\":\"二\"},{\"title\":\"三\"}]}", List.of(), List.of());

        assertThatThrownBy(() -> artifacts.consume(fixture.user, fixture.project, String.valueOf(plan.get("id"))))
                .hasMessageContaining("只有课程讲稿");
    }

    @Test void anotherUsersDraftCannotBeConsumed() {
        var fixture = fixture();
        var draft = artifacts.save(fixture.run, fixture.user, fixture.project, "script_writer",
                "SCRIPT_DRAFT", "别人的讲稿正文，不应该被当前用户消费。", List.of(), List.of());

        assertThatThrownBy(() -> artifacts.consume(fixture.user + 1, fixture.project, String.valueOf(draft.get("id"))))
                .hasMessageContaining("项目不存在");
    }

    private record Fixture(long user, long project, String run) { }

    private Fixture fixture() {
        long user = ((Number) repository.createUser("u" + UUID.randomUUID().toString().substring(0, 12), "password")
                .get("id")).longValue();
        long project = ((Number) repository.ensureProject(user, "消费测试").get("id")).longValue();
        String session = repository.openSession(user, project, null);
        return new Fixture(user, project, runs.start(user, project, session, "讲个睡前故事"));
    }
}
