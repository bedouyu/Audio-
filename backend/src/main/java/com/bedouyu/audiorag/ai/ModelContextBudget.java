package com.bedouyu.audiorag.ai;

import com.bedouyu.audiorag.ai.token.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** 在发送前检查完整请求，而不是只数用户文本或 RAG 块。此处只返回计数，绝不返回原文。 */
@Component
public class ModelContextBudget {
    private final ObjectMapper json;
    private final Environment environment;
    private final Profile fixedProfile;
    private final List<TokenCounter> counters;

    @Autowired
    public ModelContextBudget(ObjectMapper json, Environment environment, List<TokenCounter> counters) {
        this.json = json; this.environment = environment; this.fixedProfile = null;
        this.counters = List.copyOf(counters);
    }

    /** 显式 profile 构造器供离线测试与独立嵌入使用，不替未知模型猜测窗口。 */
    public ModelContextBudget(ObjectMapper json, Profile profile, List<TokenCounter> counters) {
        this.json = json; this.environment = null; this.fixedProfile = Objects.requireNonNull(profile);
        this.counters = List.copyOf(counters);
    }

    public Profile profile(String model) {
        if (fixedProfile != null) {
            if (!fixedProfile.model().equals(model))
                throw new IllegalArgumentException("预算 profile 与实际模型不匹配");
            return fixedProfile;
        }
        Long window = environment.getProperty("audiorag.llm.context-window", Long.class);
        if (window == null || window <= 0)
            throw new IllegalStateException("请显式配置 audiorag.llm.context-window；不能猜测模型上下文窗口");
        return new Profile(model, environment.getProperty("audiorag.llm.budget-profile", "configured_window"),
                window, environment.getProperty("audiorag.llm.output-reserve", Long.class, 8192L),
                environment.getProperty("audiorag.llm.protocol-overhead", Long.class, 256L),
                environment.getProperty("audiorag.llm.minimum-safety-margin", Long.class, 1024L),
                environment.getProperty("audiorag.llm.safety-margin-ratio", Double.class, 0.10),
                environment.getProperty("audiorag.llm.stream-usage-enabled", Boolean.class, true));
    }

    public Snapshot inspect(String model, Map<String, Object> request) {
        Profile profile = profile(model);
        TokenCounter counter = counters.stream().filter(candidate -> candidate.supports(model))
                .max(Comparator.comparingInt(TokenCounter::priority))
                .orElseThrow(() -> new IllegalStateException("该模型没有可用 TokenCounter"));
        String serialized = json.writeValueAsString(request);
        var input = counter.count(model, serialized);
        var messages = counter.count(model, json.writeValueAsString(request.getOrDefault("messages", List.of())));
        var tools = counter.count(model, json.writeValueAsString(request.getOrDefault("tools", List.of())));
        Map<String, Object> options = new LinkedHashMap<>(request);
        options.remove("messages"); options.remove("tools");
        var requestOptions = counter.count(model, json.writeValueAsString(options));
        long messageCount = request.get("messages") instanceof Collection<?> list ? list.size() : 0;
        long toolCount = request.get("tools") instanceof Collection<?> list ? list.size() : 0;
        // 预算中额外预留角色/消息/工具模板开销；不是声称供应商真实模板的精确成本。
        long protocol = Math.addExact(profile.protocolOverhead(), Math.addExact(messageCount * 16L, toolCount * 64L));
        long margin = Math.max(profile.minimumSafetyMargin(),
                (long) Math.ceil(Math.addExact(input.tokens(), protocol) * profile.safetyMarginRatio()));
        long total = Math.addExact(Math.addExact(input.tokens(), protocol), Math.addExact(profile.outputReserve(), margin));
        return new Snapshot(model, profile.name(), profile.contextWindow(), input.tokens(),
                profile.outputReserve(), protocol, margin, total, input.method(), input.estimated(),
                messages.tokens(), tools.tokens(), requestOptions.tokens(), total <= profile.contextWindow());
    }

    public record Profile(String model, String name, long contextWindow, long outputReserve,
                          long protocolOverhead, long minimumSafetyMargin, double safetyMarginRatio,
                          boolean streamUsageEnabled) {
        public Profile {
            if (model == null || model.isBlank() || name == null || name.isBlank()
                    || contextWindow < 1024 || contextWindow > 100_000_000
                    || outputReserve < 1 || outputReserve >= contextWindow
                    || protocolOverhead < 0 || protocolOverhead > contextWindow
                    || minimumSafetyMargin < 0 || minimumSafetyMargin >= contextWindow
                    || !Double.isFinite(safetyMarginRatio) || safetyMarginRatio < 0.0 || safetyMarginRatio > 1.0)
                throw new IllegalArgumentException("模型上下文预算配置无效");
        }
    }

    public record Snapshot(String model, String profile, long contextWindow, long inputTokens,
                           long outputReserve, long protocolOverhead, long safetyMargin, long totalReserved,
                           String countingMethod, boolean payloadCountEstimated, long messagesTokens,
                           long toolsTokens, long requestOptionsTokens, boolean withinBudget) {
        public Map<String, Object> data() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("model", model); result.put("profile", profile);
            result.put("context_window", contextWindow); result.put("input_tokens", inputTokens);
            result.put("output_reserve", outputReserve); result.put("protocol_overhead", protocolOverhead);
            result.put("safety_margin", safetyMargin); result.put("total_reserved", totalReserved);
            result.put("counting_method", countingMethod); result.put("payload_count_estimated", payloadCountEstimated);
            // 内部模板未知，因此整个请求预算始终标 estimated；API usage 才是供应商结算计数。
            result.put("estimated", true); result.put("messages_tokens", messagesTokens);
            result.put("tools_tokens", toolsTokens); result.put("request_options_tokens", requestOptionsTokens);
            result.put("within_budget", withinBudget); result.put("unit", "estimated_tokens");
            return Collections.unmodifiableMap(result);
        }
    }
}
