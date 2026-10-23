package com.bedouyu.audiorag.tools.audio;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.service.ContinuousAudioStore;
import com.bedouyu.audiorag.tools.FunctionTool;
import com.bedouyu.audiorag.tools.ToolSchemas;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 让主控看到当前音频的片段：编号、状态、字数与开头片段。
 * 改稿前先读这个，才知道要改第几段、这段现在写的是什么。
 */
@Component
public class AudioSegmentsTool implements FunctionTool {
    private final ContinuousAudioStore store;
    private final AudioToolTarget target;

    public AudioSegmentsTool(ContinuousAudioStore store, AudioToolTarget target) {
        this.store = store;
        this.target = target;
    }

    public FunctionDefinition definition() {
        return new FunctionDefinition("audio_segments",
                "读取当前项目最近一条音频的分段情况：每段编号、合成状态、字数与开头摘录。"
                        + "要改某一段讲稿前必须先调用它确认段号和内容；不传 queue_id 时取该项目最新一条音频。",
                ToolSchemas.object(Map.of("queue_id", ToolSchemas.string(36)), "queue_id"));
    }

    public Object execute(JsonNode args, AgentExecutionContext context) {
        String queueId = target.resolve(context, args.path("queue_id").asText(""));
        Map<String, Object> snapshot = store.status(context.userId, queueId);
        List<Map<String, Object>> segments = new ArrayList<>();
        if (snapshot.get("segments") instanceof List<?> values) {
            for (Object item : values) {
                if (!(item instanceof Map<?, ?> segment)) continue;
                String script = segment.get("script") instanceof String text ? text : "";
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("segment_number", segment.get("segment_number"));
                view.put("status", segment.get("status"));
                view.put("characters", script.length());
                view.put("preview", script.length() > 160 ? script.substring(0, 160) + "…" : script);
                segments.add(view);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queue_id", queueId);
        result.put("status", snapshot.get("status"));
        result.put("voice", snapshot.get("voice"));
        result.put("segments", segments);
        result.put("hint", "改稿用 revise_segment（segment_number + instruction），换音色用 revoice_segment，"
                + "改坏了用 undo_segment_revision 撤回上一版。");
        return result;
    }
}
