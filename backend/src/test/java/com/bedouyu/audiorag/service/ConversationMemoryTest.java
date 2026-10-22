package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.model.entity.ChatMessage;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.memory.ConversationMemory;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ConversationMemoryTest {
    @Test
    void removesOldWholeTurnsInsteadOfLeavingOrphanAnswers() {
        AudioRagRepository repository = mock(AudioRagRepository.class);
        when(repository.recentMessages("session")).thenReturn(List.of(
                new ChatMessage("session", "user", "旧问题" + "字".repeat(3000)),
                new ChatMessage("session", "assistant", "旧回答" + "字".repeat(3000)),
                new ChatMessage("session", "user", "新问题"),
                new ChatMessage("session", "assistant", "新回答")));
        var result = new ConversationMemory(repository).window("session");
        assertThat(result).extracting(item -> item.content()).containsExactly("新问题", "新回答");
    }
}
