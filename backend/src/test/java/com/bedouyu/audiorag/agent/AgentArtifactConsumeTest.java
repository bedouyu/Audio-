package com.bedouyu.audiorag.agent;

import com.bedouyu.audiorag.rag.OnnxEmbeddingService;
import com.bedouyu.audiorag.common.Hashing;
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
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
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
        approve(fixture, draft);
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

    @Test void revisedAndConsumedScriptsCannotLeaveARunAwaitingOrBeRevivedByALateFinish() {
        var f = fixture();
        var old = artifacts.save(f.run, f.user, f.project, "script_writer", "SCRIPT_DRAFT", "旧版正文", List.of(), List.of());
        approve(f, old);
        var latest = artifacts.save(f.run, f.user, f.project, "script_writer", "SCRIPT_DRAFT", "修订后的正文", List.of(), List.of());
        approve(f, latest);
        runs.finish(f.run, "SUCCEEDED", "已复核", Map.of());
        artifacts.consume(f.user, f.project, String.valueOf(latest.get("id")));
        assertThat(artifacts.get(f.user, f.project, String.valueOf(old.get("id"))).get("status")).isEqualTo("SUPERSEDED");
        assertThat(runs.get(f.user, f.project, f.run).get("status")).isEqualTo("SUCCEEDED");
        runs.finish(f.run, "AWAITING_CONFIRMATION", "旧快照迟到", Map.of("artifacts", List.of(old, latest)));
        assertThat(runs.get(f.user, f.project, f.run).get("status")).isEqualTo("SUCCEEDED");
    }

    @Test void directConsumptionRequiresAReviewOfTheCurrentDraftAndAFinishedRun() {
        var f = fixture();
        var old = artifacts.save(f.run, f.user, f.project, "script_writer", "SCRIPT_DRAFT", "已通过的旧稿", List.of(), List.of());
        approve(f, old);
        assertThatThrownBy(() -> artifacts.consume(f.user, f.project, String.valueOf(old.get("id"))))
                .hasMessageContaining("任务仍在运行");
        var latest = artifacts.save(f.run, f.user, f.project, "script_writer", "SCRIPT_DRAFT", "尚未复核的修订稿", List.of(), List.of());
        runs.finish(f.run, "SUCCEEDED", "完成", Map.of());
        assertThatThrownBy(() -> artifacts.consume(f.user, f.project, String.valueOf(latest.get("id"))))
                .hasMessageContaining("已通过复核报告");
        assertThat(artifacts.get(f.user, f.project, String.valueOf(latest.get("id"))).get("status")).isEqualTo("DRAFT");
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

    private void approve(Fixture f, Map<String, Object> draft) {
        artifacts.save(f.run, f.user, f.project, "review_agent", "REVIEW_REPORT", json.writeValueAsString(Map.of(
                "reviewed_artifact_id", draft.get("id"), "content_hash", Hashing.sha256(String.valueOf(draft.get("content"))),
                "approved", true, "issues", List.of(), "reason", "逐项检查通过",
                "checks", Map.of("topic", true, "genre", true, "language", true, "ending", true,
                        "length", true, "citations", true, "readable", true))), List.of(String.valueOf(draft.get("id"))), List.of());
    }

    private Fixture fixture() {
        long user = ((Number) repository.createUser("u" + UUID.randomUUID().toString().substring(0, 12), "password")
                .get("id")).longValue();
        long project = ((Number) repository.ensureProject(user, "消费测试").get("id")).longValue();
        String session = repository.openSession(user, project, null);
        return new Fixture(user, project, runs.start(user, project, session, "讲个睡前故事"));
    }
}
