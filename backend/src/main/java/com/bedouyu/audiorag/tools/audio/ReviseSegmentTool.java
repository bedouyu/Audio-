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
 * 提出某一段的修改，原稿与音频保持不变，等待用户在差异视图确认。
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
    public long minimumTimeoutMillis() { return 210_000L; }

    public FunctionDefinition definition() {
        return new FunctionDefinition("revise_segment",
                "在当前讲稿的基础上提出某一整段的修改，等待用户审阅红绿差异并确认。"
                        + "instruction 要写清楚怎么改（例如「太书面了，改口语一点，保留原来的例子」），"
                        + "不要整段重发全文。工具只生成待确认修改，不会写回原稿或合成音频；你不能替用户确认。",
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
                .reviseSegment(context.userId, queueId, number, instruction, null, context::assertActive);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queue_id", queueId);
        result.put("segment_number", number);
        result.put("status", snapshot.get("status"));
        result.put("instruction", instruction);
        result.put("script_revision", AudioToolTarget.revisionSummary(snapshot));
        result.put("note", snapshot.containsKey("script_revision") ? "已生成待确认修改；请用户在右侧查看红绿差异，确认后才应用并合成音频。" : "正文没有变化，原稿与音频保持不变。");
        return result;
    }
}
