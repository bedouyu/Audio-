package com.bedouyu.audiorag.memory;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.memory.strategy.MemoryFragment;
import com.bedouyu.audiorag.model.entity.*;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MemoryContextServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AudioRagRepository repository = mock(AudioRagRepository.class);
    private final MemoryCheckpointStore store = mock(MemoryCheckpointStore.class);
    private final MemoryContextService memory = new MemoryContextService(repository, store, json, "hybrid", 6000);
    private ChatMessage row(long id, String role, String text, String turn) {
        var value = new ChatMessage("session", role, text, turn);
        ReflectionTestUtils.setField(value, "id", id); return value;
    }
    private void current() {
        var rows = List.of(row(11, "user", "更正：现在需要十分钟课程。", "t-new"), row(12, "assistant", "收到。", "t-new"));
        when(repository.memoryMessages(1, 7, "session", 400)).thenReturn(rows);
    }
    @Test void checkpointOutsideWindowIsVerifiedAndOrdinalIsRebuiltFromSourceIds() {
        current(); var old = row(1, "user", "目标是给初学者介绍概率论。", "t-old");
        var note = new MemoryFragment("m1", "user", 0, old.getContent().length(), old.getContent(), "NOTE", 100, "t-old");
        var checkpoint = new ConversationMemoryCheckpoint("session", 1, 7);
        checkpoint.replace(12, json.writeValueAsString(List.of(note)), json.writeValueAsString(Map.of("m1",
                Hashing.sha256("user\nt-old\n" + old.getContent()))));
        when(store.get(1, 7, "session")).thenReturn(Optional.of(checkpoint));
        when(repository.memorySources(eq(1L), eq(7L), eq("session"), anyList())).thenReturn(List.of(old));
        var result = memory.prepare(1, 7, "session", "继续", "hybrid");
        assertThat(result.pack().fragments()).anySatisfy(f -> {
            assertThat(f.sourceId()).isEqualTo("m1"); assertThat(f.ordinal()).isZero();
        });
        assertThat(json.writeValueAsString(result.modelData()).length()).isLessThanOrEqualTo(6000);
    }
    @Test void corruptFrontierIsDiscardedAndDerivedCacheCanBeRepaired() {
        current(); var checkpoint = new ConversationMemoryCheckpoint("session", 1, 7);
        checkpoint.replace(Long.MAX_VALUE, "[]", "{}");
        when(store.get(1, 7, "session")).thenReturn(Optional.of(checkpoint));
        var result = memory.prepare(1, 7, "session", "继续", "hybrid");
        assertThat(result.checkpointLoaded()).isFalse(); assertThat(result.checkpointThroughId()).isEqualTo(12);
        verify(store).save(eq(1L), eq(7L), eq("session"), eq(12L), anyString(), anyString(), eq(true));
    }
    @Test void badJsonNeverBecomesHistoryAndOriginalRowsRemainUsable() {
        current(); var checkpoint = new ConversationMemoryCheckpoint("session", 1, 7);
        checkpoint.replace(12, "not-json", "{}");
        when(store.get(1, 7, "session")).thenReturn(Optional.of(checkpoint));
        var result = memory.prepare(1, 7, "session", "继续", "hybrid");
        assertThat(result.pack().fragments()).isNotEmpty();
        assertThat(result.checkpointWarnings()).anyMatch(w -> w.contains("未使用派生摘记"));
    }
    @Test void escapedControlCharactersAreBudgetedAsSerializedJsonAndReadMakesProgress() {
        var text = String.valueOf((char) 0).repeat(2500);
        when(repository.memorySources(1, 7, "session", List.of(1L))).thenReturn(List.of(row(1, "user", text, "t1")));
        var result = memory.read(1, 7, "session", "m1", 0, 2500);
        assertThat(json.writeValueAsString(result).length()).isLessThanOrEqualTo(7000);
        assertThat((int) result.get("end")).isBetween(1, 2499); assertThat(result.get("has_more")).isEqualTo(true);
    }
    @Test void tooSmallUnicodeRangeAndUnknownScopeSourceAreRejected() {
        when(repository.memorySources(1, 7, "session", List.of(1L))).thenReturn(List.of(row(1, "user", "😀text", "t1")));
        assertThatThrownBy(() -> memory.read(1, 7, "session", "m1", 0, 1)).hasMessageContaining("Unicode");
        assertThatThrownBy(() -> memory.read(1, 7, "session", "m999", 0, 10)).hasMessageContaining("current session");
    }
    @Test void ambiguousLeftBoundaryIsNotTurnedIntoCompletedLegacyNotes() {
        List<ChatMessage> rows = new ArrayList<>();
        for (int i = 1; i <= 400; i++) rows.add(row(i, i % 2 == 1 ? "user" : "assistant",
                i == 1 ? "目标是边界中不确定的要求" : "普通消息", null));
        when(repository.memoryMessages(1, 7, "session", 400)).thenReturn(rows);
        var result = memory.prepare(1, 7, "session", "continue", "hybrid");
        assertThat(result.checkpointWarnings()).anyMatch(w -> w.contains("左边界"));
        assertThat(result.pack().fragments()).noneMatch(f -> f.sourceId().equals("m1"));
    }
}
