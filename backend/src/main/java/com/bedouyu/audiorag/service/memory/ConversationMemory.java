package com.bedouyu.audiorag.service.memory;

import com.bedouyu.audiorag.model.dto.ModelMessage;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/** 旧版窗口基线，仅为兼容已有测试保留。生产聊天已改为有归属校验的MemoryContextService。 */
@Deprecated(forRemoval = false)
@Service
public class ConversationMemory {
    private final AudioRagRepository repository;
    public ConversationMemory(AudioRagRepository repository) { this.repository = repository; }
    public List<ModelMessage> window(String sessionId) {
        var all = repository.recentMessages(sessionId);
        List<ModelMessage> result = new ArrayList<>();
        int used = 0;
        for (int index = all.size() - 1; index > 0; index -= 2) {
            var answer = all.get(index); var question = all.get(index - 1);
            if (!"assistant".equals(answer.getRole()) || !"user".equals(question.getRole())) continue;
            int size = question.getContent().length() + answer.getContent().length();
            if (used + size > 6000) break;
            result.add(0, new ModelMessage(answer.getRole(), answer.getContent()));
            result.add(0, new ModelMessage(question.getRole(), question.getContent()));
            used += size;
        }
        return List.copyOf(result);
    }
}
