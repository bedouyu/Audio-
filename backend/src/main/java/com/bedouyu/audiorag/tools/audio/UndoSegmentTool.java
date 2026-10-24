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

/** 撤回上一版也先生成待确认修改。 */
@Component
public class UndoSegmentTool implements FunctionTool {
    private final ObjectProvider<ContinuousAudioService> audio;
    private final AudioToolTarget target;

    public UndoSegmentTool(ObjectProvider<ContinuousAudioService> audio, AudioToolTarget target) {
        this.audio = audio;
        this.target = target;
    }

    public boolean hasSideEffects() { return true; }

    public FunctionDefinition definition() {
        return new FunctionDefinition("undo_segment_revision",
                "提出把某一段改回上一次修订前文字的待确认修改，用户说「改回去」「撤回」时使用。"
                        + "工具不直接应用或合成；用户须在差异视图确认，你不能替用户确认。",
                ToolSchemas.object(Map.of(
                        "segment_number", ToolSchemas.integer(1, 200),
                        "queue_id", ToolSchemas.string(36)), "segment_number"));
    }

    public Object execute(JsonNode args, AgentExecutionContext context) {
        int number = args.path("segment_number").asInt(0);
        if (number < 1) throw new IllegalArgumentException("segment_number 必须从 1 开始");
        String queueId = target.resolve(context, args.path("queue_id").asText(""));
        Map<String, Object> snapshot = audio.getObject().undoRevision(context.userId, queueId, number, context::assertActive);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queue_id", queueId);
        result.put("segment_number", number);
        result.put("status", snapshot.get("status"));
        result.put("script_revision", AudioToolTarget.revisionSummary(snapshot));
        result.put("note", "回退修改已生成，等待用户在右侧确认；原稿与音频保持不变。");
        return result;
    }
}
