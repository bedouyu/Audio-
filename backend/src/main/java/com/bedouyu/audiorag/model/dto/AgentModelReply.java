package com.bedouyu.audiorag.model.dto;
import java.util.*;

/** reasoningContent 仅在本次请求的协议回灌中保留，禁止写入事件日志或显示为执行轨迹。 */
public record AgentModelReply(String content, String reasoningContent,
                              List<FunctionCall> calls, String finishReason) {
    public AgentModelReply { calls = List.copyOf(calls); }
    public Map<String, Object> assistantMessage() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content == null ? "" : content);
        if (reasoningContent != null && !reasoningContent.isBlank())
            message.put("reasoning_content", reasoningContent);
        if (!calls.isEmpty()) message.put("tool_calls", calls.stream().map(FunctionCall::wire).toList());
        return message;
    }
}
