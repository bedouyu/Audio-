package com.bedouyu.audiorag.agent;
import java.util.Set;

/** 每个 Agent 的上下文、工具权限、输出类型和轮次上限。 */
public record AgentProfile(String id, String title, String prompt, Set<String> tools,
                           String artifactKind, int maxSteps) {
    public AgentProfile { tools = Set.copyOf(tools); }
}
