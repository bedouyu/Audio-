package com.bedouyu.audiorag.tools;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

@Component
public class FunctionToolRegistry {
    private final Map<String, FunctionTool> tools = new LinkedHashMap<>();
    public FunctionToolRegistry(List<AgentTool> searches, List<FunctionTool> functions) {
        functions.forEach(this::register);
        // 原生版本可替代同名旧搜索适配器；仍保留 AgentTool 给旧课程入口使用。
        searches.stream().filter(tool -> !tools.containsKey(tool.id()))
                .forEach(tool -> register(new SearchAdapter(tool)));
    }
    private void register(FunctionTool tool) {
        if (tools.putIfAbsent(tool.definition().name(), tool) != null)
            throw new IllegalStateException("工具名称重复：" + tool.definition().name());
    }
    public FunctionTool require(String name) {
        FunctionTool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("工具未注册：" + name);
        return tool;
    }
    public List<FunctionDefinition> definitions(Set<String> allowed) {
        return allowed.stream().sorted().map(name -> require(name).definition()).toList();
    }
    private static class SearchAdapter implements FunctionTool {
        private final AgentTool tool;
        SearchAdapter(AgentTool tool) { this.tool = tool; }
        public FunctionDefinition definition() {
            return new FunctionDefinition(tool.id(),
                    "knowledge_search".equals(tool.id()) ? "检索当前项目本机知识库，返回证据和引用编号。"
                            : "联网查找公开资料，返回证据和引用编号；不能上传本机文件。",
                    ToolSchemas.object(Map.of("query", ToolSchemas.string(1000),
                            "limit", ToolSchemas.integer(1, 6)), "query"));
        }
        public Object execute(JsonNode args, AgentExecutionContext context) {
            var hits = tool.execute(context.projectId, args.path("query").asText(), args.path("limit").asInt(4));
            context.assertActive();
            var evidence = context.evidence.register(hits);
            return Map.of("status", evidence.isEmpty() ? "NO_RESULTS" : "OK",
                    "evidence", evidence, "hit_count", evidence.size());
        }
    }
}
