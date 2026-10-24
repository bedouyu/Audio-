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
 * 在当前音频的讲稿后面追加新内容（“再生成一些”“多来几句”）。
 * 不动已有段落，新增内容先等待用户确认。
 */
@Component
public class ExtendScriptTool implements FunctionTool {
    private final ObjectProvider<ContinuousAudioService> audio;
    private final AudioToolTarget target;

    public ExtendScriptTool(ObjectProvider<ContinuousAudioService> audio, AudioToolTarget target) {
        this.audio = audio;
        this.target = target;
    }

    public boolean hasSideEffects() { return true; }
    public long minimumTimeoutMillis() { return 270_000L; }

    public FunctionDefinition definition() {
        return new FunctionDefinition("extend_script",
                "提出在现有讲稿后面追加新内容的修改，等待用户审阅绿色新增内容并确认。"
                        + "用户说“再生成一些”“多来几句”“按这个格式再来 10 句”“换成六级水平再加几句”时用它；"
                        + "工具不直接写回或合成，你不能替用户确认。要整篇重排用 rewrite_script，改某一段用 revise_segment。",
                ToolSchemas.object(Map.of(
                        "instruction", ToolSchemas.string(1000),
                        "queue_id", ToolSchemas.string(36)), "instruction"));
    }

    @Override
    public Object execute(JsonNode args, AgentExecutionContext context) {
        String instruction = args.path("instruction").asText("").strip();
        if (instruction.length() < 2) throw new IllegalArgumentException("请写下要续写什么、多长、什么难度");
        String queueId = target.resolve(context, args.path("queue_id").asText(""));
        var service = audio.getIfAvailable();
        if (service == null) throw new IllegalStateException("音频服务不可用");
        Map<String, Object> snapshot = service.extendWhole(context.userId, queueId, instruction, context::assertActive);
        int segments = snapshot.get("segments") instanceof List<?> list ? list.size() : 0;
        return Map.of("queue_id", String.valueOf(snapshot.get("queue_id")),
                "status", String.valueOf(snapshot.get("status")),
                "segments", segments,
                "script_revision", AudioToolTarget.revisionSummary(snapshot),
                "note", "续写修改已生成，等待用户在右侧确认；原稿与音频保持不变。");
    }
}
