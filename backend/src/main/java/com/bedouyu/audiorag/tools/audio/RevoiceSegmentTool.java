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

/** 把整条音频换成另一个音色重新念，讲稿不变。 */
@Component
public class RevoiceSegmentTool implements FunctionTool {
    private final ObjectProvider<ContinuousAudioService> audio;
    private final AudioToolTarget target;

    public RevoiceSegmentTool(ObjectProvider<ContinuousAudioService> audio, AudioToolTarget target) {
        this.audio = audio;
        this.target = target;
    }

    public boolean hasSideEffects() { return true; }

    public FunctionDefinition definition() {
        return new FunctionDefinition("revoice_segment",
                "把这条音频换成另一个音色重新合成：讲稿一个字都不改，只重念。"
                        + "音色名必须来自当前音色列表（可由调用方给出），且要支持内容语言；"
                        + "中英混排内容需要同时支持中文和英文的音色。",
                ToolSchemas.object(Map.of(
                        "voice", ToolSchemas.string(64),
                        "queue_id", ToolSchemas.string(36)), "voice"));
    }

    public Object execute(JsonNode args, AgentExecutionContext context) {
        String voice = args.path("voice").asText("").strip();
        if (voice.isEmpty()) throw new IllegalArgumentException("voice 不能为空");
        String queueId = target.resolve(context, args.path("queue_id").asText(""));
        Map<String, Object> snapshot = audio.getObject().revoice(context.userId, queueId, voice);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queue_id", queueId);
        result.put("voice", voice);
        result.put("status", snapshot.get("status"));
        result.put("note", "已按新音色重新合成，讲稿与内容未改动。");
        return result;
    }
}
