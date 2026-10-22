package com.bedouyu.audiorag.agent.audio.specialist;
import com.bedouyu.audiorag.agent.*;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class ContentReviewAgent implements SpecialistAgent {
    public AgentProfile profile() {
        // 讲稿可能分页读取：每页一次 read_artifact，加 validate_script 与最终输出，5 步偏紧。
        return new AgentProfile("review_agent", "内容复核", "review-agent",
                Set.of("read_artifact", "validate_script", "knowledge_search"), "REVIEW_REPORT", 8);
    }
}
