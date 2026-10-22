package com.bedouyu.audiorag.tools.agent;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.core.guard.TaskSpecification;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.service.AgentRunStore;
import com.bedouyu.audiorag.tools.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** 模型可补充开放式目标需要的产物，但不能删去用户明确请求，也不能在执行后更改标准。 */
@Component
public class DeclareTaskSpecificationTool implements FunctionTool {
    private final AgentRunStore runs;
    public DeclareTaskSpecificationTool(AgentRunStore runs) { this.runs = runs; }
    public boolean hasSideEffects() { return true; }
    public FunctionDefinition definition() {
        return new FunctionDefinition("declare_task_specification", "委派前声明本次任务最低产物，补充开放式目标。只可增加要求，不授予合成/计划写入权限。",
                ToolSchemas.object(Map.of("required_artifacts", Map.of("type", "array", "maxItems", 4,
                        "items", Map.of("type", "string", "enum", TaskSpecification.ALLOWED.stream().sorted().toList()))), "required_artifacts"));
    }
    public Object execute(JsonNode args, AgentExecutionContext context) {
        context.assertActive();
        if (!"supervisor".equals(context.agentId) || context.taskSpecificationFrozen)
            throw new IllegalArgumentException("任务规格只能在主控委派前声明");
        List<String> added = new ArrayList<>(); args.path("required_artifacts").forEach(kind -> added.add(kind.asText()));
        context.taskSpecification = context.taskSpecification.augment(added);
        context.taskSpecificationDeclared = true;
        context.assertActive();
        runs.saveSpecification(context.runId, context.taskSpecification.data());
        context.assertActive();
        context.taskSpecificationFrozen = true;
        return Map.of("status", "OK", "task_specification", context.taskSpecification.data());
    }
}
