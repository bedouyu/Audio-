package com.bedouyu.audiorag.tools.audio;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.service.ContinuousAudioService;
import com.bedouyu.audiorag.tools.FunctionTool;
import com.bedouyu.audiorag.tools.ToolSchemas;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 直接改写某一段讲稿并重新合成这一段。属于有副作用的工具：
 * 旧稿与旧音频都进审计与延迟清理，改坏了可以用 undo_segment_revision 撤回。
 */
@Component
public class ReviseSegmentTool implements FunctionTool {
    private final ObjectProvider<ContinuousAudioService> audio;
    private final AudioToolTarget target;

    public ReviseSegmentTool(ObjectProvider<ContinuousAudioService> audio, AudioToolTarget target) {
        this.audio = audio;
        this.target = target;
    }

    public boolean hasSideEffects() { return true; }

    public FunctionDefinition definition() {
        return new FunctionDefinition("revise_segment",
                "在当前讲稿的基础上改写某一整段，然后只重新合成这一段。"
                        + "instruction 要写清楚怎么改（例如「太书面了，改口语一点，保留原来的例子」），"
                        + "不要整段重发全文。改完会返回该段新的字数与片段状态；只改这一段，其余段不动。",
                ToolSchemas.object(Map.of(
                        "segment_number", ToolSchemas.integer(1, 200),
                        "instruction", ToolSchemas.string(300),
                        "queue_id", ToolSchemas.string(36)), "segment_number", "instruction"));
    }

    public Object execute(JsonNode args, AgentExecutionContext context) {
        int number = args.path("segment_number").asInt(0);
        String instruction = args.path("instruction").asText("").strip();
        if (number < 1) throw new IllegalArgumentException("segment_number 必须从 1 开始");
        if (instruction.length() < 2) throw new IllegalArgumentException("instruction 太短，请说明要改成什么样");
        String queueId = target.resolve(context, args.path("queue_id").asText(""));
        Map<String, Object> snapshot = audio.getObject()
                .reviseSegment(context.userId, queueId, number, instruction, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queue_id", queueId);
        result.put("segment_number", number);
        result.put("status", snapshot.get("status"));
        result.put("instruction", instruction);
        result.put("note", "已按意见改写这一段并开始重新合成；讲稿可在音频卡片查看，改坏了用 undo_segment_revision。");
        return result;
    }
}
