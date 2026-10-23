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

/** 撤回上一版：把某一段改回上一次修订前的讲稿并重新合成。 */
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
                "撤回某一段的上一版改稿：把它改回上一次修订前的文字并重新合成这一段。"
                        + "用户说「改回去」「撤回」「还是用原来那版」时使用；再调用一次会回到被撤回的那一版。",
                ToolSchemas.object(Map.of(
                        "segment_number", ToolSchemas.integer(1, 200),
                        "queue_id", ToolSchemas.string(36)), "segment_number"));
    }

    public Object execute(JsonNode args, AgentExecutionContext context) {
        int number = args.path("segment_number").asInt(0);
        if (number < 1) throw new IllegalArgumentException("segment_number 必须从 1 开始");
        String queueId = target.resolve(context, args.path("queue_id").asText(""));
        Map<String, Object> snapshot = audio.getObject().undoRevision(context.userId, queueId, number);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queue_id", queueId);
        result.put("segment_number", number);
        result.put("status", snapshot.get("status"));
        result.put("restored_characters", snapshot.get("restored_characters"));
        result.put("note", "已恢复到上一版讲稿并重新合成。");
        return result;
    }
}
