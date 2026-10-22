package com.bedouyu.audiorag.tools.audio;
import com.bedouyu.audiorag.common.SpeechTiming;
import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.tools.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class EstimateDurationTool implements FunctionTool {
    public FunctionDefinition definition() { return new FunctionDefinition("estimate_duration",
            "按文本自身语言估算朗读时长：中文约 260 非空白字符/分钟，英文约 150 词（约 850 字符）/分钟，中英混排按两者加权。"
                    + "返回 target_characters（达到 target_minutes 应有的篇幅）与 within_tolerance（是否在 ±20% 内）。"
                    + "这是估算，不是已测量的音频时长。",
            ToolSchemas.object(Map.of("text", ToolSchemas.string(24000),
                    "target_minutes", ToolSchemas.integer(1, 60)), "text")); }
    public Object execute(JsonNode args, AgentExecutionContext context) {
        String text = args.path("text").asText();
        int minutes = Math.max(1, Math.min(60, args.path("target_minutes").asInt(5)));
        int characters = SpeechTiming.nonWhitespaceCharacters(text);
        double density = SpeechTiming.charactersPerMinute(text);
        double estimatedSeconds = SpeechTiming.estimatedSeconds(text);
        double targetSeconds = minutes * 60.0;
        double deviation = targetSeconds <= 0 ? 0 : (estimatedSeconds - targetSeconds) / targetSeconds;
        var result = new LinkedHashMap<String, Object>();
        result.put("status", "OK");
        result.put("characters", characters);
        result.put("cjk_characters", SpeechTiming.cjkCharacters(text));
        result.put("latin_words", SpeechTiming.latinWords(text));
        result.put("characters_per_minute", Math.round(density));
        result.put("estimated_seconds", Math.round(estimatedSeconds));
        result.put("estimated_minutes", Math.round(estimatedSeconds / 6.0) / 10.0);
        result.put("target_minutes", minutes);
        result.put("target_characters", Math.round(minutes * density));
        result.put("deviation_ratio", Math.round(deviation * 100) / 100.0);
        result.put("within_tolerance", Math.abs(deviation) <= 0.2);
        result.put("estimated", true);
        return result;
    }
}
