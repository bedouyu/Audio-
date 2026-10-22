package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.agent.audio.AudioAgent.ChatCommand;
import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.controller.AiController;
import com.bedouyu.audiorag.memory.ConversationTurnLock;
import com.bedouyu.audiorag.model.dto.ChatRequest;
import com.bedouyu.audiorag.model.entity.AgentRunRecord;
import com.bedouyu.audiorag.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRequestCoordinatorTest {
    private static final String REQUEST = "7ab79a00-2127-4c91-890d-674ab100001f";
    private static final String REQUEST_LOCK = "request:1:7:" + REQUEST;
    private final ObjectMapper json = new ObjectMapper();
    private final AudioRagRepository repository = mock(AudioRagRepository.class);
    private final AgentRunRecordRepository records = mock(AgentRunRecordRepository.class);
    private final AgentArtifactStore artifacts = mock(AgentArtifactStore.class);
    private final AgentRunStore runs = spy(new AgentRunStore(records, repository, json, artifacts));
    private final ConversationTurnLock locks = spy(new ConversationTurnLock());
    private final AgentRequestCoordinator coordinator = new AgentRequestCoordinator(repository, runs, locks, json);
    private final AtomicReference<AgentRunRecord> saved = new AtomicReference<>();

    @BeforeEach void useAnInMemoryRunRepositoryWithoutAnyModelOrServices() {
        when(repository.ownsProject(1, 7)).thenReturn(true);
        when(repository.openSession(eq(1L), eq(7L), nullable(String.class))).thenReturn("session");
        when(records.lockedByRequest(1, 7, REQUEST)).thenAnswer(ignored -> Optional.ofNullable(saved.get()));
        when(records.findByUserIdAndProjectIdAndRequestId(1, 7, REQUEST)).thenAnswer(ignored -> Optional.ofNullable(saved.get()));
        when(records.saveAndFlush(any(AgentRunRecord.class))).thenAnswer(call -> {
            AgentRunRecord run = call.getArgument(0); saved.set(run); return run;
        });
        when(repository.events("session")).thenReturn(List.of());
        when(artifacts.forRun(anyLong(), anyLong(), anyString())).thenReturn(List.of());
    }

    @Test void retryingTheSameRequestReplaysWithoutOpeningASessionOrStartingAnotherRun() {
        ChatCommand command = command("解释概率", null, "recent_window", Set.of());
        String run;
        try (var first = coordinator.begin(1, command)) {
            run = first.runId(); assertThat(first.replay()).isNull(); assertThat(first.sessionId()).isEqualTo("session");
            complete("SUCCEEDED");
        }
        try (var replay = coordinator.begin(1, command)) {
            assertThat(replay.replay()).containsEntry("run_id", run).containsEntry("session_id", "session").containsEntry("answer", "原回答");
            assertThat(replay.runId()).isNull(); assertThat(replay.sessionId()).isNull();
        }
        verify(repository, times(1)).openSession(1, 7, null);
        verify(runs, times(1)).start(eq(1L), eq(7L), eq("session"), eq("解释概率"), eq(REQUEST), anyString());
        verify(records, times(1)).saveAndFlush(any());
        verify(locks, times(2)).leave(REQUEST_LOCK); verify(locks, times(1)).leave("session");
    }

    @Test void changedMessageSessionStrategyOrRequirementsWithTheSameIdCause409() {
        ChatCommand original = command("解释概率", null, "recent_window", Set.of());
        try (var first = coordinator.begin(1, original)) { complete("SUCCEEDED"); }
        for (ChatCommand changed : List.of(command("不同问题", null, "recent_window", Set.of()),
                command("解释概率", "other-session", "recent_window", Set.of()),
                command("解释概率", null, "hybrid", Set.of()),
                command("解释概率", null, "recent_window", Set.of("SCRIPT_DRAFT")))) {
            assertThatThrownBy(() -> coordinator.begin(1, changed)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT)).hasMessageContaining("不同的请求内容");
        }
        verify(repository, times(1)).openSession(anyLong(), anyLong(), nullable(String.class));
        verify(records, times(1)).saveAndFlush(any());
        // A rejected retry released its request lock, so the original body remains replayable.
        try (var replay = coordinator.begin(1, original)) { assertThat(replay.replay()).isNotNull(); }
    }

    @Test void canonicalRequestIdsAndSetOrderingKeepTheSameFingerprint() {
        var firstKinds = new LinkedHashSet<>(List.of("SCRIPT_DRAFT", "LEARNING_PLAN"));
        var secondKinds = new LinkedHashSet<>(List.of("LEARNING_PLAN", "SCRIPT_DRAFT"));
        ChatCommand original = command("生成讲稿和学习计划", null, null, firstKinds);
        try (var first = coordinator.begin(1, original)) { complete("AWAITING_CONFIRMATION"); }
        ChatCommand same = new ChatCommand(original.message(), null, 7, null, REQUEST.toUpperCase(Locale.ROOT), secondKinds);
        try (var replay = coordinator.begin(1, same)) { assertThat(replay.replay()).containsEntry("status", "AWAITING_CONFIRMATION"); }
        assertThat(saved.get().getRequestId()).isEqualTo(REQUEST);
        verify(records, times(1)).saveAndFlush(any());
    }

    @Test void implicitAndExplicitEmptyTaskRequirementsCannotShareAnIdempotencyKey() {
        var original = command("解释概率", null, null, null);
        try (var first = coordinator.begin(1, original)) { complete("SUCCEEDED"); }
        assertThatThrownBy(() -> coordinator.begin(1, command("解释概率", null, null, Set.of())))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
        verify(records, times(1)).saveAndFlush(any());
    }

    @Test void runningFailedInterruptedAndCancelledRequestsAreNotAutomaticallyRestarted() {
        ChatCommand command = command("解释概率", null, null, Set.of());
        try (var first = coordinator.begin(1, command)) { assertThat(first.runId()).isNotBlank(); }
        for (String status : List.of("RUNNING", "FAILED", "INTERRUPTED", "LIMIT_REACHED", "CANCELLED")) {
            saved.get().finish(status, "原任务", null);
            assertThatThrownBy(() -> coordinator.begin(1, command)).isInstanceOfSatisfying(ApiException.class,
                    error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT)).hasMessageContaining(status);
        }
        verify(repository, times(1)).openSession(anyLong(), anyLong(), nullable(String.class));
        verify(records, times(1)).saveAndFlush(any());
    }

    @Test void failureToStartARunReleasesBothLocks() {
        ChatCommand command = command("解释概率", null, null, Set.of());
        doThrow(new IllegalStateException("无法保存run")).when(runs).start(eq(1L), eq(7L), anyString(), anyString(), eq(REQUEST), anyString());
        assertThatThrownBy(() -> coordinator.begin(1, command)).hasMessageContaining("无法保存run");
        verify(locks).leave("session"); verify(locks).leave(REQUEST_LOCK);
        doCallRealMethod().when(runs).start(eq(1L), eq(7L), anyString(), anyString(), eq(REQUEST), anyString());
        try (var retry = coordinator.begin(1, command)) { assertThat(retry.runId()).isNotBlank(); }
    }

    @Test void failureToOpenASessionAlsoReleasesTheRequestLock() {
        ChatCommand command = command("解释概率", null, null, Set.of());
        when(repository.openSession(1, 7, null)).thenThrow(new IllegalStateException("无法创建会话"));
        assertThatThrownBy(() -> coordinator.begin(1, command)).hasMessageContaining("无法创建会话");
        verify(locks).leave(REQUEST_LOCK); verify(locks, never()).leave("session");
        doReturn("session").when(repository).openSession(1, 7, null);
        try (var retry = coordinator.begin(1, command)) { assertThat(retry.runId()).isNotBlank(); }
    }

    @Test void aBusySessionDoesNotLoseItsOriginalLockWhenTheRequestFails() {
        ChatCommand command = command("解释概率", null, null, Set.of()); locks.enter("session");
        assertThatThrownBy(() -> coordinator.begin(1, command)).hasMessageContaining("已有任务");
        verify(locks).leave(REQUEST_LOCK); verify(locks, never()).leave("session");
        assertThatThrownBy(() -> locks.enter("session")).hasMessageContaining("已有任务");
        locks.leave("session");
        try (var retry = coordinator.begin(1, command)) { assertThat(retry.runId()).isNotBlank(); }
    }

    @Test void aFailureDuringTheReservedTaskReleasesBothLocksAndCloseIsIdempotent() {
        ChatCommand command = command("解释概率", null, null, Set.of());
        var reservation = coordinator.begin(1, command);
        assertThatThrownBy(() -> { try (reservation) { throw new IllegalStateException("模型任务失败"); } }).hasMessageContaining("模型任务失败");
        reservation.close();
        verify(locks, times(1)).leave("session"); verify(locks, times(1)).leave(REQUEST_LOCK);
        assertThatCode(() -> { locks.enter("session"); locks.enter(REQUEST_LOCK); }).doesNotThrowAnyException();
        locks.leave("session"); locks.leave(REQUEST_LOCK);
    }

    @Test void controllerPreservesRequestIdentityForReplayAndRejectsChangedBodies() {
        var auth = mock(AuthService.class); var agent = mock(AudioAgent.class);
        when(auth.requireUser("Bearer test")).thenReturn(Map.of("id", 1L));
        when(agent.chat(eq(1L), any(ChatCommand.class))).thenAnswer(call -> {
            ChatCommand command = call.getArgument(1);
            try (var reservation = coordinator.begin(1, command)) {
                if (reservation.replay() != null) return reservation.replay();
                complete("SUCCEEDED");
                return Map.of("status", "SUCCEEDED", "run_id", reservation.runId(), "session_id", reservation.sessionId(), "answer", "原回答");
            }
        });
        var controller = new AiController(auth, agent, null, repository, null, null, null, null);
        ChatRequest body = json.readValue(json.writeValueAsString(Map.of("message", "解释概率", "project_id", 7,
                "request_id", REQUEST, "memory_strategy", "recent_window", "required_artifacts", List.of())), ChatRequest.class);
        var first = controller.chat("Bearer test", body); var second = controller.chat("Bearer test", body);
        assertThat(second).containsEntry("run_id", first.get("run_id"));
        assertThatThrownBy(() -> controller.chat("Bearer test", new ChatRequest("改换问题", null, 7L, "recent_window", REQUEST, Set.of())))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
        verify(repository, times(1)).openSession(anyLong(), anyLong(), nullable(String.class));
        verify(records, times(1)).saveAndFlush(any());
    }

    @Test void invalidRequestIdsCannotCreateSessionsOrRuns() {
        assertThatThrownBy(() -> coordinator.begin(1, new ChatCommand("问题", null, 7, null, "invalid", Set.of())))
                .hasMessageContaining("UUID");
        verify(repository, never()).openSession(anyLong(), anyLong(), nullable(String.class));
        verify(records, never()).saveAndFlush(any()); verifyNoInteractions(locks);
    }

    private ChatCommand command(String message, String session, String strategy, Set<String> required) {
        return new ChatCommand(message, session, 7, strategy, REQUEST, required);
    }
    private void complete(String status) {
        saved.get().finish(status, "任务已完成", json.writeValueAsString(Map.of("answer", "原回答", "citations", List.of())));
    }
}
