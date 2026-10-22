package com.bedouyu.audiorag.agent.audio.specialist;
import com.bedouyu.audiorag.agent.*;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class SupervisorAgent implements SpecialistAgent {
    public AgentProfile profile() {
        return new AgentProfile("supervisor", "音频主控", "supervisor",
                Set.of("knowledge_search", "knowledge_catalog", "web_search", "project_context", "delegate_agent", "read_artifact",
                        "memory_search", "memory_read", "task_read", "declare_task_specification"), "", 10);
    }
}
