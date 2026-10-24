package com.bedouyu.audiorag.agent;

import com.bedouyu.audiorag.agent.audio.*;
import com.bedouyu.audiorag.ai.AiModelGateway;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.core.guard.TaskSpecificationResolver;
import com.bedouyu.audiorag.core.loop.AgentBudget;
import com.bedouyu.audiorag.core.loop.AudioRetrievalLoop;
import com.bedouyu.audiorag.memory.MemoryContextService;
import com.bedouyu.audiorag.rag.QdrantService;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.*;
import com.bedouyu.audiorag.tools.research.WebSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 覆盖用户音频入口与任务回收，不能用聊天入口成功来替代这里的验收。 */
class AudioAgentCourseIntegrityTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AudioRagRepository repository = mock(AudioRagRepository.class);
    private final AiModelGateway model = mock(AiModelGateway.class);
    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final AgentArtifactStore artifacts = mock(AgentArtifactStore.class);
    private final AudioAgent agent = new AudioAgent(mock(QdrantService.class), repository,
            mock(WebSearchService.class), model, mock(LearningPlanService.class), mock(AudioRetrievalLoop.class),
            mock(MemoryContextService.class), orchestrator, json, mock(AgentRequestCoordinator.class),
            mock(AgentRunStore.class), new TaskSpecificationResolver(), artifacts);

    AudioAgentCourseIntegrityTest() {
        when(repository.ownsProject(1, 2)).thenReturn(true);
        when(orchestrator.available()).thenReturn(true);
    }

    @Test void audioEntryActuallyRequestsAgentsAndConsumesTheReviewedDraftFromItsOwnRun() {
        var draft = script("own", "小狐狸在月光下找到了回家的路。", "DRAFT");
        prepare(List.of(draft, review(draft, true)), null);

        assertThat(generate().script()).isEqualTo(draft.get("content"));

        verify(artifacts).consume(1, 2, "own");
        verify(artifacts, never()).list(anyLong(), anyLong());
        verifyNoInteractions(model);
    }

    @Test void aStoppedRunCanRecoverOnlyItsOwnCurrentReviewedScript() {
        var draft = script("own", "预算中断前已完成且复核通过的完整正文。", "DRAFT");
        prepare(List.of(draft, review(draft, true)), new AgentBudget.AgentStopped("LIMIT_REACHED", "轮数耗尽"));

        assertThat(generate().script()).isEqualTo(draft.get("content"));

        verify(artifacts).consume(1, 2, "own");
        verifyNoInteractions(model);
    }

    @Test void aFailedRunCannotPickUpAnotherConcurrentRequestsProjectDraftOrUseSingleShotFallback() {
        var unrelated = script("another-request", "另一位并发请求正在生成的讲稿。", "DRAFT");
        when(artifacts.list(1, 2)).thenReturn(List.of(unrelated, review(unrelated, true)));
        prepare(List.of(), new IllegalStateException("模型连接中断"));

        assertThatThrownBy(this::generate).hasMessageContaining("多智能体内容准备失败");

        verify(artifacts, never()).list(anyLong(), anyLong());
        verify(artifacts, never()).consume(anyLong(), anyLong(), anyString());
        verifyNoInteractions(model);
    }

    @Test void revisionMustNotFallBackToASupersededApprovedDraftOrAnUnreviewedReplacement() {
        var old = script("old", "已被新稿取代的正文。", "SUPERSEDED");
        var latest = script("latest", "尚未经过复核的修订稿。", "DRAFT");
        prepare(List.of(old, review(old, true), latest), new AgentBudget.AgentStopped("LIMIT_REACHED", "修订中断"));

        assertThatThrownBy(this::generate).hasMessageContaining("多智能体内容准备失败");

        verify(artifacts, never()).consume(anyLong(), anyLong(), anyString());
        verifyNoInteractions(model);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "TOOL_TIMEOUT", "TOOL_RUNTIME_STOPPED", "CONTEXT_LIMIT"})
    void cancellationOrUncertainToolOutcomeCannotConsumeEvenAnAlreadyReviewedDraft(String status) {
        var draft = script("own", "已写好但用户已经取消了本次任务。", "DRAFT");
        prepare(List.of(draft, review(draft, true)), new AgentBudget.AgentStopped(status, "任务已取消"));

        assertThatThrownBy(this::generate).hasMessageContaining("任务已取消");

        verify(artifacts, never()).consume(anyLong(), anyLong(), anyString());
        verifyNoInteractions(model);
    }

    @Test void rejectedReviewDoesNotAuthorizeDirectAudioDelivery() {
        var draft = script("own", "复核明确判定仍有问题的正文。", "DRAFT");
        prepare(List.of(draft, review(draft, false)), null);

        assertThatThrownBy(this::generate).hasMessageContaining("通过当前版本复核");

        verify(artifacts, never()).consume(anyLong(), anyLong(), anyString());
        verifyNoInteractions(model);
    }

    private AudioAgent.CoursePlan generate() {
        return agent.createCourse(1, new AudioAgent.CourseCommand("写一个原创中文故事", 2, 1, null));
    }
    private void prepare(List<Map<String, Object>> outputs, RuntimeException failure) {
        when(artifacts.forRun(1, 2, "own-run")).thenReturn(outputs);
        doAnswer(call -> {
            AudioAgent.ChatCommand command = call.getArgument(1);
            assertThat(command.audioRequest()).isTrue();
            assertThat(command.requiredArtifacts()).containsExactlyInAnyOrder("SCRIPT_DRAFT", "REVIEW_REPORT");
            Consumer<String> started = call.getArgument(4); started.accept("own-run");
            if (failure != null) throw failure;
            return Map.of("run_id", "own-run", "artifacts", outputs);
        }).when(orchestrator).chat(eq(1L), any(AudioAgent.ChatCommand.class), isNull(), any(), any());
    }
    private Map<String, Object> script(String id, String text, String status) {
        return Map.of("kind", "SCRIPT_DRAFT", "id", id, "run_id", "own-run", "content", text,
                "status", status, "citations", List.of());
    }
    private Map<String, Object> review(Map<String, Object> draft, boolean approved) {
        return Map.of("kind", "REVIEW_REPORT", "id", "review-" + draft.get("id"), "run_id", "own-run", "content",
                json.writeValueAsString(Map.of("reviewed_artifact_id", draft.get("id"),
                        "content_hash", Hashing.sha256(String.valueOf(draft.get("content"))), "approved", approved,
                        "issues", approved ? List.of() : List.of("需要修订"), "reason", "已逐项检查正文",
                        "checks", Map.of("topic", true, "genre", true, "language", true, "ending", true,
                                "length", true, "citations", true, "readable", true))));
    }
}
