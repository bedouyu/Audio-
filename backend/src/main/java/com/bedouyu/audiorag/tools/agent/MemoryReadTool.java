package com.bedouyu.audiorag.tools.agent;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.memory.MemoryContextService;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.tools.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

@Component
public class MemoryReadTool implements FunctionTool {
    private final MemoryContextService memory;
    public MemoryReadTool(MemoryContextService memory) { this.memory = memory; }
    public FunctionDefinition definition() {
        return new FunctionDefinition("memory_read", "按source_id分段读取当前会话真实历史消息；source_id来自memory。不能读取其他会话。",
                ToolSchemas.object(Map.of("source_id", ToolSchemas.string(30), "start", ToolSchemas.integer(0, 2000000),
                        "maximum_chars", ToolSchemas.integer(1, 2500)), "source_id"));
    }
    public Object execute(JsonNode args, AgentExecutionContext context) {
        return memory.read(context.userId, context.projectId, context.sessionId, args.path("source_id").asText(),
                args.path("start").asInt(0), args.path("maximum_chars").asInt(1800));
    }
}
