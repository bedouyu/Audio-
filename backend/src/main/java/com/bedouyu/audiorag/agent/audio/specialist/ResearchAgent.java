package com.bedouyu.audiorag.agent.audio.specialist;
import com.bedouyu.audiorag.agent.*;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class ResearchAgent implements SpecialistAgent {
    public AgentProfile profile() {
        return new AgentProfile("research_agent", "资料研究", "research-agent",
                Set.of("knowledge_search", "knowledge_catalog", "web_search", "project_context", "read_artifact"), "RESEARCH_REPORT", 6);
    }
}
