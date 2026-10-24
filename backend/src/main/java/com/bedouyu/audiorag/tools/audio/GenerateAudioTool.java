package com.bedouyu.audiorag.tools.audio;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.service.ContinuousAudioService;
import com.bedouyu.audiorag.tools.FunctionTool;
import com.bedouyu.audiorag.tools.ToolSchemas;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 按需合成音频片段：用户说“继续生成”“把剩下的都生成出来”时用。
 * 讲稿已经存在，这里只触发合成，不改变文字。
 */
@Component
public class GenerateAudioTool implements FunctionTool {
    private final ObjectProvider<ContinuousAudioService> audio;
    private final AudioToolTarget target;

    public GenerateAudioTool(ObjectProvider<ContinuousAudioService> audio, AudioToolTarget target) {
        this.audio = audio;
        this.target = target;
    }

    public boolean hasSideEffects() { return true; }

    public FunctionDefinition definition() {
        return new FunctionDefinition("generate_audio",
                "为当前音频合成还没生成的片段（讲稿不变）。用户说“继续生成”“生成下一段”“把剩下的都生成出来”时用。"
                        + "不传 segments 表示只生成下一段；传 0 或很大的数表示把剩余的全都生成。",
                ToolSchemas.object(Map.of(
                        "segments", ToolSchemas.integer(0, 50),
                        "queue_id", ToolSchemas.string(36))));
    }

    @Override
    public Object execute(JsonNode args, AgentExecutionContext context) {
        String queueId = target.resolve(context, args.path("queue_id").asText(""));
        var service = audio.getIfAvailable();
        if (service == null) throw new IllegalStateException("音频服务不可用");
        Map<String, Object> before = service.status(context.userId, queueId);
        int pending = 0;
        if (before.get("segments") instanceof List<?> segments) {
            for (Object raw : segments) {
                if (raw instanceof Map<?, ?> segment && "PENDING".equals(String.valueOf(segment.get("status")))) pending++;
            }
        }
        int requested = args.path("segments").isMissingNode() ? 1 : args.path("segments").asInt(0);
        if (pending == 0)
            return Map.of("queue_id", queueId, "status", before.get("status"), "started", 0,
                    "note", "所有片段都已合成，没有待生成的段落");
        int count = requested <= 0 ? pending : Math.min(requested, pending);
        Map<String, Object> snapshot = service.refill(context.userId, queueId, count);
        return Map.of("queue_id", String.valueOf(snapshot.get("queue_id")),
                "status", String.valueOf(snapshot.get("status")),
                "started", count, "pending_before", pending,
                "note", "已开始合成 " + count + " 段，完成后会显示在右侧");
    }
}
