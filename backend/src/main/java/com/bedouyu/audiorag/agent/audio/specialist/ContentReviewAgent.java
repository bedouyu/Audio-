package com.bedouyu.audiorag.agent.audio.specialist;
import com.bedouyu.audiorag.agent.*;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class ContentReviewAgent implements SpecialistAgent {
    public AgentProfile profile() {
        return new AgentProfile("review_agent", "内容复核", "review-agent",
                Set.of("read_artifact", "validate_script", "knowledge_search"), "REVIEW_REPORT", 5);
    }
}
