package com.bedouyu.audiorag.tools.audio;

import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.service.ContinuousAudioStore;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AudioToolTargetTest {
    private final ContinuousAudioStore store = mock(ContinuousAudioStore.class);
    private final AudioToolTarget target = new AudioToolTarget(store);

    @Test void selectedHistoricalQueueWinsOverTheLatestQueueAndIsInheritedByChildren() {
        var context = context(); context.audioQueueId = "selected";
        var child = context.child("writer", Set.of());
        assertThat(target.resolve(child, "")).isEqualTo("selected");
        verify(store).requireOwnedInProject(1, 7, "selected");
        verify(store, never()).latestQueue(anyLong(), anyLong());
        verify(store, never()).latestQueueForUser(anyLong());
    }

    @Test void aToolCannotOverrideTheUsersSelectedQueue() {
        var context = context(); context.audioQueueId = "selected";
        assertThatThrownBy(() -> target.resolve(context, "other")).hasMessageContaining("不一致");
        verifyNoInteractions(store);
    }

    @Test void invalidExplicitQueueDoesNotFallBackToAnotherQueue() {
        doThrow(new IllegalArgumentException("项目不匹配"))
                .when(store).requireOwnedInProject(1, 7, "foreign");
        assertThatThrownBy(() -> target.resolve(context(), "foreign")).hasMessageContaining("项目不匹配");
        verify(store, never()).latestQueue(anyLong(), anyLong());
        verify(store, never()).latestQueueForUser(anyLong());
    }

    @Test void aMissingProjectQueueNeverFallsBackAcrossProjects() {
        when(store.latestQueue(1, 7)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> target.resolve(context(), "")).hasMessageContaining("当前内容空间没有");
        verify(store, never()).latestQueueForUser(anyLong());
    }

    @Test void projectFallbackIsPinnedForTheWholeRunEvenIfTheLatestQueueChanges() {
        when(store.latestQueue(1, 7)).thenReturn(Optional.of("first"), Optional.of("second"));
        var context = context();
        assertThat(target.resolve(context.child("writer", Set.of()), "")).isEqualTo("first");
        assertThat(target.resolve(context, "")).isEqualTo("first");
        verify(store, times(1)).latestQueue(1, 7);
    }

    private AgentExecutionContext context() {
        return new AgentExecutionContext("run", "session", "supervisor", 1, 7,
                new AgentBudget(40, 48, Duration.ofSeconds(5)), new EvidenceLedger(), null, null, ignored -> { });
    }
}
