package com.bedouyu.audiorag.tools.audio;
import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.tools.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.Map;

@Component
public class EstimateDurationTool implements FunctionTool {
    public FunctionDefinition definition() { return new FunctionDefinition("estimate_duration",
            "根据约每分钟 260 个非空白字符估算讲稿长度；这不是已测量的实际音频时长。",
            ToolSchemas.object(Map.of("text", ToolSchemas.string(24000),
                    "target_minutes", ToolSchemas.integer(1, 60)), "text")); }
    public Object execute(JsonNode args, AgentExecutionContext context) {
        int chars = args.path("text").asText().replaceAll("\\s+", "").length();
        return Map.of("status", "OK", "characters", chars, "estimated_seconds", Math.round(chars * 60.0 / 260),
                "target_minutes", args.path("target_minutes").asInt(5), "estimated", true);
    }
}
