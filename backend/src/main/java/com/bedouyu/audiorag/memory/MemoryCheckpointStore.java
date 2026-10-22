package com.bedouyu.audiorag.memory;

import com.bedouyu.audiorag.model.entity.ConversationMemoryCheckpoint;
import com.bedouyu.audiorag.repository.ConversationMemoryCheckpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/** 校验派生记录归属；调用者还须先验证原始session。乐观锁避免无声覆盖并发检查点。 */
@Service
@Transactional
public class MemoryCheckpointStore {
    private final ConversationMemoryCheckpointRepository repository;
    public MemoryCheckpointStore(ConversationMemoryCheckpointRepository repository) { this.repository = repository; }
    @Transactional(readOnly = true)
    public Optional<ConversationMemoryCheckpoint> get(long user, long project, String session) {
        var value = repository.findById(session);
        value.ifPresent(row -> {
            if (row.getUserId() != user || row.getProjectId() != project)
                throw new IllegalArgumentException("Memory checkpoint does not belong to this session owner");
        });
        return value;
    }
    public void save(long user, long project, String session, long through, String notesJson, String hashesJson) {
        save(user, project, session, through, notesJson, hashesJson, false);
    }
    public void save(long user, long project, String session, long through, String notesJson, String hashesJson, boolean repair) {
        var value = get(user, project, session).orElseGet(() -> new ConversationMemoryCheckpoint(session, user, project));
        if (repair) value.repair(through, notesJson, hashesJson);
        else value.replace(through, notesJson, hashesJson);
        repository.saveAndFlush(value);
    }
    public void deleteProject(long project) { repository.deleteByProjectId(project); }
}
