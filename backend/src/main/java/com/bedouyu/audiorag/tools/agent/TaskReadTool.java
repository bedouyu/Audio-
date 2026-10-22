package com.bedouyu.audiorag.tools.agent;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.service.*;
import com.bedouyu.audiorag.tools.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;
import java.util.*;

/** 失败任务的原目标未必进入公开messages，须从同会话真实run恢复，而不是让模型猜。 */
@Component
public class TaskReadTool implements FunctionTool {
    private final AgentRunStore runs;
    private final AgentArtifactStore artifacts;
    private final ObjectMapper json;
    public TaskReadTool(AgentRunStore runs, AgentArtifactStore artifacts, ObjectMapper json) {
        this.runs = runs; this.artifacts = artifacts; this.json = json;
    }
    public FunctionDefinition definition() {
        return new FunctionDefinition("task_read", "分段恢复同一会话的真实任务目标、状态和产物编号。原目标截断时使用；不会执行或重放任务。",
                ToolSchemas.object(Map.of("run_id", ToolSchemas.string(36), "start", ToolSchemas.integer(0, 4000),
                        "maximum_chars", ToolSchemas.integer(2, 2000)), "run_id"));
    }
    public Object execute(JsonNode args, AgentExecutionContext context) {
        String id = args.path("run_id").asText();
        var run = runs.get(context.userId, context.projectId, id);
        if (!context.sessionId.equals(run.get("session_id"))) throw new IllegalArgumentException("任务不属于当前会话");
        String goal = String.valueOf(run.get("goal"));
        int start = args.path("start").asInt(0);
        if (start > goal.length() || boundary(goal, start)) throw new IllegalArgumentException("目标原文范围无效");
        int end = Math.min(goal.length(), start + args.path("maximum_chars").asInt(1500));
        if (boundary(goal, end)) end--;
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "OK"); result.put("run_id", id); result.put("task_status", run.get("status"));
        result.put("summary", run.get("summary")); result.put("start", start); result.put("end", end);
        result.put("goal", goal.substring(start, end)); result.put("has_more", end < goal.length());
        result.put("artifacts", artifacts.forRun(context.userId, context.projectId, id).stream().limit(6)
                .map(a -> Map.of("id", a.get("id"), "kind", a.get("kind"), "status", a.get("status"))).toList());
        while (json.writeValueAsString(result).length() > 7000) {
            end = start + (end - start) / 2;
            if (boundary(goal, end)) end--;
            if (end <= start) throw new IllegalArgumentException("Task metadata exceeds budget");
            result.put("goal", goal.substring(start, end)); result.put("end", end); result.put("has_more", end < goal.length());
        }
        return result;
    }
    private static boolean boundary(String value, int position) {
        return position > 0 && position < value.length() && Character.isHighSurrogate(value.charAt(position - 1))
                && Character.isLowSurrogate(value.charAt(position));
    }
}
