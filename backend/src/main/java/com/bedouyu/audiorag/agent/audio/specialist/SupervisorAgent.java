package com.bedouyu.audiorag.agent.audio.specialist;
import com.bedouyu.audiorag.agent.*;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class SupervisorAgent implements SpecialistAgent {
    public AgentProfile profile() {
        return new AgentProfile("supervisor", "音频主控", "supervisor",
                Set.of("knowledge_search", "knowledge_catalog", "web_search", "project_context", "delegate_agent", "read_artifact",
                        "memory_search", "memory_read", "task_read", "declare_task_specification",
                        // 改稿与换音色：直接作用于当前项目的音频，改坏了有 undo_segment_revision 兜底。
                        "audio_segments", "revise_segment", "revoice_segment", "undo_segment_revision"), "", 12);
    }
}
