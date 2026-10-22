package com.bedouyu.audiorag.tools;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ToolRegistry {
    private final Map<String, AgentTool> tools;
    public ToolRegistry(List<AgentTool> discovered) {
        tools = discovered.stream().collect(Collectors.toUnmodifiableMap(AgentTool::id, Function.identity()));
    }
    public AgentTool require(String id) {
        AgentTool tool = tools.get(id);
        if (tool == null) throw new IllegalArgumentException("工具未注册：" + id);
        return tool;
    }
}
