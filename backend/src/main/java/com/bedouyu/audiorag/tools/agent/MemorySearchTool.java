package com.bedouyu.audiorag.tools.agent;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.memory.MemoryContextService;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.tools.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** 当前主控会话的按需记忆检索。没有用户/项目/会话ID输入，不能扩大范围。 */
@Component
public class MemorySearchTool implements FunctionTool {
    private final MemoryContextService memory;
    public MemorySearchTool(MemoryContextService memory) { this.memory = memory; }
    public FunctionDefinition definition() {
        return new FunctionDefinition("memory_search", "按需恢复当前会话的历史目标、约束或相关讨论。"
                + "recent_window近期，query_recall关键词历史召回，extractive_notes原文摘记+近期，hybrid混合。"
                + "摘记不等于已验证事实，后续更正与当前请求优先。返回source_id和范围，可memory_read重读。",
                ToolSchemas.object(Map.of("query", ToolSchemas.string(1000),
                        "strategy", Map.of("type", "string", "enum", List.of("recent_window", "query_recall", "extractive_notes", "hybrid")),
                        "budget_chars", ToolSchemas.integer(1000, 6000)), "query"));
    }
    public Object execute(JsonNode arguments, AgentExecutionContext context) {
        var prepared = memory.prepare(context.userId, context.projectId, context.sessionId, arguments.path("query").asText(),
                arguments.path("strategy").asText("hybrid"), arguments.path("budget_chars").asInt(3000), false);
        var result = new LinkedHashMap<>(prepared.modelData());
        result.put("status", prepared.pack().fragments().isEmpty() ? "NO_RESULTS" : "OK");
        result.put("actual_strategy", prepared.pack().strategy());
        return result;
    }
}
