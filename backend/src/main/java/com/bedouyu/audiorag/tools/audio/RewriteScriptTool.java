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
 * 整篇重写当前音频的讲稿：用户要改的是格式或结构时用它
 * （例如「不要导读，只要英语-汉语-英语两遍-下一句」）。
 * 结果直接写回同一批分段并只重新合成第 1 段；只改某一段用 revise_segment。
 */
@Component
public class RewriteScriptTool implements FunctionTool {
    private final ObjectProvider<ContinuousAudioService> audio;
    private final AudioToolTarget target;

    public RewriteScriptTool(ObjectProvider<ContinuousAudioService> audio, AudioToolTarget target) {
        this.audio = audio;
        this.target = target;
    }

    public boolean hasSideEffects() { return true; }

    public FunctionDefinition definition() {
        return new FunctionDefinition("rewrite_script",
                "整篇重写当前音频的讲稿，直接写回右侧稿子并只重新合成第 1 段。"
                        + "用户要改的是格式或结构（去掉开场白/导读/串场/结尾总结、调整句序、改成某种循环）时用它；"
                        + "只改某一段的措辞请用 revise_segment，不要用这个工具重写整篇。",
                ToolSchemas.object(Map.of(
                        "instruction", ToolSchemas.string(1000),
                        "queue_id", ToolSchemas.string(36)), "instruction"));
    }

    @Override
    public Object execute(JsonNode args, AgentExecutionContext context) {
        String instruction = args.path("instruction").asText("").strip();
        if (instruction.length() < 2) throw new IllegalArgumentException("请写下整篇讲稿想怎么改");
        String queueId = target.resolve(context, args.path("queue_id").asText(""));
        var service = audio.getIfAvailable();
        if (service == null) throw new IllegalStateException("音频服务不可用");
        Map<String, Object> snapshot = service.rewriteWhole(context.userId, queueId, instruction);
        int segments = snapshot.get("segments") instanceof List<?> list ? list.size() : 0;
        return Map.of("queue_id", String.valueOf(snapshot.get("queue_id")),
                "status", String.valueOf(snapshot.get("status")),
                "segments", segments,
                "note", "整篇已重写并写回讲稿，只重新合成了第 1 段");
    }
}
