package com.bedouyu.audiorag.core;

import com.bedouyu.audiorag.agent.AgentProfile;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.ai.AgentModelGateway;
import com.bedouyu.audiorag.config.PromptTemplates;
import com.bedouyu.audiorag.core.guard.*;
import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.model.dto.*;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.AgentArtifactStore;
import com.bedouyu.audiorag.tools.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 完成判定测试：工具“被调用”不等于成功，最终回答也不能代替所要求的产物。 */
class CompletionSemanticsTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void noResultsTruncationAndErrorsDoNotSatisfyResearchPrerequisites() {
        for (String status : List.of("NO_RESULTS", "TRUNCATED", "ERROR")) {
            var engine = engine(status);
            assertThatThrownBy(() -> engine.run(research(), "研究概率", List.of(), context("research_agent"), ignored -> {}))
                    .as(status).hasMessageContaining("真实检索");
        }
    }

    @Test void aSuccessfulObservationCanSatisfyResearchPrerequisites() {
        assertThat(engine("OK").run(research(), "研究概率", List.of(), context("research_agent"), ignored -> {}))
                .isEqualTo("已查到资料，以下是研究报告。");
    }

    @Test void supervisorCannotReplaceRequiredArtifactsWithCompletionClaims() {
        var store = mock(AgentArtifactStore.class);
        when(store.forRun(1, 7, "run")).thenReturn(List.of(Map.of("kind", "RESEARCH_REPORT")));
        var validator = new AgentOutputValidator(json, store);
        var context = context("supervisor");
        context.taskSpecification = new TaskSpecification(Set.of("SCRIPT_DRAFT", "REVIEW_REPORT"), "USER_API");
        var profile = new AgentProfile("supervisor", "主控", "", Set.of(), "", 3);
        assertThatThrownBy(() -> validator.validate(profile, "生成讲稿，不要公式", "全部完成", Set.of(), context))
                .hasMessageContaining("缺少真实产物");
        var script = script("a", "最终讲稿", "DRAFT");
        when(store.forRun(1, 7, "run")).thenReturn(List.of(script, review(script, true)));
        assertThatCode(() -> validator.validate(profile, "生成讲稿，不要公式", "草稿待确认", Set.of(), context))
                .doesNotThrowAnyException();
    }

    @Test void aScriptWithoutReviewIsNotACompletedTask() {
        var store = mock(AgentArtifactStore.class);
        var validator = new AgentOutputValidator(json, store);
        var context = context("supervisor");
        var profile = new AgentProfile("supervisor", "主控", "", Set.of(), "", 3);

        var script = script("a", "最终讲稿", "DRAFT");
        when(store.forRun(1, 7, "run")).thenReturn(List.of(script));
        assertThatThrownBy(() -> validator.validate(profile, "讲个睡前故事", "讲稿已备好", Set.of(), context))
                .hasMessageContaining("必须先复核");

        when(store.forRun(1, 7, "run")).thenReturn(List.of(
                script, review(script, true)));
        assertThatCode(() -> validator.validate(profile, "讲个睡前故事", "讲稿已复核", Set.of(), context))
                .doesNotThrowAnyException();
    }

    @Test void finalRevisionCannotBorrowAnOldOrRejectedReview() {
        var store = mock(AgentArtifactStore.class);
        var validator = new AgentOutputValidator(json, store);
        var context = context("supervisor");
        context.audioRequest = true;
        var profile = new AgentProfile("supervisor", "主控", "", Set.of(), "", 3);
        var old = script("old", "第一份讲稿", "SUPERSEDED");
        var latest = script("latest", "修订后的讲稿", "DRAFT");
        for (var outputs : List.of(List.of(old, review(old, true), latest),
                List.of(latest, review(latest, false)),
                List.of(latest, review(latest, true), review(latest, false)),
                List.of(latest, review(script("latest", "不同内容", "DRAFT"), true)))) {
            when(store.forRun(1, 7, "run")).thenReturn(outputs);
            assertThatThrownBy(() -> validator.validate(profile, "讲个故事", "讲稿已复核", Set.of(), context))
                    .hasMessageContaining("当前版本并通过");
        }
        when(store.forRun(1, 7, "run")).thenReturn(List.of(old, review(old, false), latest, review(latest, true)));
        assertThatCode(() -> validator.validate(profile, "讲个故事", "修订稿已复核", Set.of(), context))
                .doesNotThrowAnyException();
    }

    @Test void chatMayReturnABoundRejectedReviewForUserRevisionWithoutAuthorizingAudio() {
        var store = mock(AgentArtifactStore.class);
        var validator = new AgentOutputValidator(json, store);
        var context = context("supervisor");
        var profile = new AgentProfile("supervisor", "主控", "", Set.of(), "", 3);
        var script = script("a", "仍需改进的讲稿", "DRAFT");
        when(store.forRun(1, 7, "run")).thenReturn(List.of(script, review(script, false)));
        assertThatCode(() -> validator.validate(profile, "先出草稿并复核", "复核未通过，请继续修订", Set.of(), context))
                .doesNotThrowAnyException();
        context.audioRequest = true;
        assertThatThrownBy(() -> validator.validate(profile, "直接生成音频", "准备合成", Set.of(), context))
                .hasMessageContaining("当前版本并通过");
    }

    private Map<String, Object> script(String id, String text, String status) {
        return Map.of("kind", "SCRIPT_DRAFT", "id", id, "run_id", "run", "content", text, "status", status);
    }
    private Map<String, Object> review(Map<String, Object> script, boolean approved) {
        return Map.of("kind", "REVIEW_REPORT", "run_id", "run", "content", json.writeValueAsString(Map.of(
                "reviewed_artifact_id", script.get("id"), "content_hash", Hashing.sha256(String.valueOf(script.get("content"))),
                "approved", approved, "issues", approved ? List.of() : List.of("还需要修订"), "reason", "逐项核验后的结论",
                "checks", Map.of("topic", true, "genre", true, "language", true, "ending", true,
                        "length", true, "citations", true, "readable", true))));
    }

    private ReActLoopEngine engine(String status) {
        var model = mock(AgentModelGateway.class);
        var first = new AgentModelReply("", "", List.of(new FunctionCall("call_1", "knowledge_search", "{}")), "tool_calls");
        var answer = new AgentModelReply("已查到资料，以下是研究报告。", "", List.of(), "stop");
        // 循环按用途选择模型（5 参重载）；Mockito 的默认方法不会转发，必须按实际调用签名桩。
        when(model.decide(anyList(), anyList(), any(), anyLong(), anyString())).thenReturn(first, answer, answer);
        var tool = mock(FunctionTool.class);
        when(tool.definition()).thenReturn(new FunctionDefinition("knowledge_search", "查询",
                ToolSchemas.object(Map.of())));
        when(tool.execute(any(), any())).thenReturn(Map.of("status", status));
        var registry = new FunctionToolRegistry(List.of(), List.of(tool));
        var repository = mock(AudioRagRepository.class);
        when(repository.ownsProject(1, 7)).thenReturn(true);
        var executor = new FunctionToolExecutor(registry, new ToolArgumentValidator(), repository, json);
        var prompts = mock(PromptTemplates.class); when(prompts.read(anyString())).thenReturn("测试提示词");
        return new ReActLoopEngine(model, registry, executor, prompts,
                new AgentOutputValidator(json, mock(AgentArtifactStore.class)), json, "structured");
    }
    private AgentProfile research() {
        return new AgentProfile("research_agent", "研究", "test", Set.of("knowledge_search"), "RESEARCH_REPORT", 3);
    }
    private AgentExecutionContext context(String agent) {
        return new AgentExecutionContext("run", "session", agent, 1, 7,
                new AgentBudget(), new EvidenceLedger(), null, null, ignored -> {});
    }
}
