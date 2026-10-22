package com.bedouyu.audiorag.tools.agent;
import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.tools.*;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

@Component
public class ProjectContextTool implements FunctionTool {
    private final AudioRagRepository repository;
    private final LearningPlanService learning;
    private final AgentArtifactStore artifacts;
    public ProjectContextTool(AudioRagRepository repository, LearningPlanService learning, AgentArtifactStore artifacts) {
        this.repository = repository; this.learning = learning; this.artifacts = artifacts;
    }
    public FunctionDefinition definition() { return new FunctionDefinition("project_context",
            "读取当前项目主题、资料概况、学习进度和已有产物编号，不读取其他项目。", ToolSchemas.object(Map.of())); }
    public Object execute(JsonNode args, AgentExecutionContext context) {
        return Map.of("status", "OK", "topic", repository.projectTopic(context.userId, context.projectId),
                "documents", repository.knowledgeRecords(context.userId, context.projectId).stream().limit(12)
                    .map(d -> Map.of("id", d.get("id"), "title", d.get("title"))).toList(),
                "learning_points", learning.list(context.projectId).stream().limit(16).toList(),
                "artifacts", artifacts.list(context.userId, context.projectId).stream().limit(8)
                    .map(a -> Map.of("id", a.get("id"), "kind", a.get("kind"), "agent_id", a.get("agent_id"),
                            "artifact_status", a.get("status"))).toList());
    }
}
