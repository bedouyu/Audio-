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
 * 结果生成待确认修改；只改某一段用 revise_segment。
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
    public long minimumTimeoutMillis() { return 270_000L; }

    public FunctionDefinition definition() {
        return new FunctionDefinition("rewrite_script",
                "提出当前音频整篇讲稿的修改，等待用户审阅红绿差异并确认，不直接写回或合成音频。"
                        + "用户要整篇改变语言、体裁、格式或结构（例如英文改为中文中国散文、去掉导读、重排正文）时用它；"
                        + "instruction 必须保留用户要求的目标语言与体裁，允许按明确要求翻译或重写，不要保留英文来绕过校验。"
                        + "只改某一段的措辞请用 revise_segment；你不能替用户确认修改。",
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
        Map<String, Object> snapshot = service.rewriteWhole(context.userId, queueId, instruction, context::assertActive);
        int segments = snapshot.get("segments") instanceof List<?> list ? list.size() : 0;
        return Map.of("queue_id", String.valueOf(snapshot.get("queue_id")),
                "status", String.valueOf(snapshot.get("status")),
                "segments", segments,
                "script_revision", AudioToolTarget.revisionSummary(snapshot),
                "note", snapshot.containsKey("script_revision") ? "整篇修改已生成，等待用户在右侧确认；原稿与音频保持不变。" : "正文没有变化，原稿与音频保持不变。");
    }
}
