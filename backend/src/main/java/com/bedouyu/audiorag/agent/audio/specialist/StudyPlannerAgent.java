package com.bedouyu.audiorag.agent.audio.specialist;
import com.bedouyu.audiorag.agent.*;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class StudyPlannerAgent implements SpecialistAgent {
    public AgentProfile profile() {
        return new AgentProfile("study_planner", "学习规划", "study-planner",
                Set.of("project_context", "read_artifact"), "LEARNING_PLAN", 4);
    }
}
