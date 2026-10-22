package com.bedouyu.audiorag.tools.agent;
import com.bedouyu.audiorag.agent.AgentRegistry;
import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.tools.*;
import com.bedouyu.audiorag.service.AgentRunStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

@Component
public class DelegateAgentTool implements FunctionTool {
    private final AgentRegistry agents;
    private final AgentRunStore runs;
    public DelegateAgentTool(AgentRegistry agents, AgentRunStore runs) { this.agents = agents; this.runs = runs; }
    public boolean hasSideEffects() { return true; }
    public FunctionDefinition definition() {
        var ids = agents.profiles().stream().filter(a -> !"supervisor".equals(a.id())).map(a -> a.id()).toList();
        return new FunctionDefinition("delegate_agent", "委派专业 Agent。规划、资料研究、讲稿编写和复核会返回持久化产物；传入相关 artifact_ids。",
                ToolSchemas.object(Map.of("agent_id", Map.of("type", "string", "enum", ids),
                        "task", ToolSchemas.string(3000), "artifact_ids", ToolSchemas.strings(4)), "agent_id", "task"));
    }
    public Object execute(JsonNode args, AgentExecutionContext context) {
        context.assertActive();
        if (context.delegate == null) throw new IllegalArgumentException("子 Agent 不能递归委派");
        if (!context.taskSpecificationDeclared) throw new IllegalArgumentException("请先声明开放式目标的task specification，再委派");
        runs.saveSpecification(context.runId, context.taskSpecification.data());
        context.assertActive();
        context.taskSpecificationFrozen = true;
        List<String> ids = new ArrayList<>();
        for (var id : args.path("artifact_ids")) ids.add(id.asText());
        return context.delegate.run(context, args.path("agent_id").asText(), args.path("task").asText(), ids);
    }
}
