package com.bedouyu.audiorag.ai;

import com.bedouyu.audiorag.ai.token.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ModelContextBudgetTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void conservativeCounterCountsChineseEmojiAndEnglishBytesNotCharacters() {
        String text = "数学😀English";
        var count = new ConservativeTokenCounter().count("test", text);
        assertThat(count.tokens()).isEqualTo(text.getBytes(StandardCharsets.UTF_8).length);
        assertThat(count.tokens()).isGreaterThan(text.length());
        assertThat(count.estimated()).isTrue();
    }
    @Test void countsSystemHistoryToolsAndAllRequestOptions() {
        Map<String, Object> request = request();
        var snapshot = budget(20_000, 1000).inspect("test", request);
        assertThat(snapshot.inputTokens()).isEqualTo(json.writeValueAsString(request).getBytes(StandardCharsets.UTF_8).length);
        assertThat(snapshot.messagesTokens()).isPositive();
        assertThat(snapshot.toolsTokens()).isPositive();
        assertThat(snapshot.requestOptionsTokens()).isPositive();
        assertThat(snapshot.totalReserved()).isEqualTo(snapshot.inputTokens() + snapshot.protocolOverhead()
                + snapshot.outputReserve() + snapshot.safetyMargin());
        assertThat(snapshot.protocolOverhead()).isEqualTo(256 + 3 * 16 + 64);
    }
    @Test void toolSchemaAloneCanPushRequestOverBudget() {
        var budget = budget(5000, 500);
        Map<String, Object> body = new LinkedHashMap<>(request());
        assertThat(budget.inspect("test", body).withinBudget()).isTrue();
        body.put("tools", List.of(Map.of("description", "大型工具说明".repeat(1000))));
        var snapshot = budget.inspect("test", body);
        assertThat(snapshot.withinBudget()).isFalse();
        assertThat(snapshot.toolsTokens()).isGreaterThan(5000);
    }
    @Test void outputReserveAndMarginAreNotAvailableToPrompt() {
        var budget = budget(3000, 1800);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", List.of(Map.of("content", "x".repeat(1000))));
        var snapshot = budget.inspect("test", body);
        assertThat(snapshot.inputTokens()).isLessThan(snapshot.contextWindow());
        assertThat(snapshot.withinBudget()).isFalse();
    }
    @Test void safetyMarginIsRatioOrMinimumWhicheverIsLarger() {
        var budget = budget(20_000, 1000);
        var small = budget.inspect("test", Map.of("messages", List.of()));
        assertThat(small.safetyMargin()).isEqualTo(128);
        var large = budget.inspect("test", Map.of("messages", List.of(Map.of("content", "a".repeat(4000)))));
        assertThat(large.safetyMargin()).isEqualTo((long) Math.ceil((large.inputTokens() + large.protocolOverhead()) * 0.1));
    }
    @Test void exactPayloadTokenizerDoesNotClaimExactProviderRequestTemplate() {
        TokenCounter modelSpecific = new TokenCounter() {
            public boolean supports(String model) { return "test".equals(model); }
            public int priority() { return 20; }
            public Count count(String model, String text) { return new Count(text.length(), "fixture_tokenizer", false); }
        };
        var budget = new ModelContextBudget(json, profile(20_000, 1000),
                List.of(new ConservativeTokenCounter(), modelSpecific));
        var snapshot = budget.inspect("test", request());
        assertThat(snapshot.countingMethod()).isEqualTo("fixture_tokenizer");
        assertThat(snapshot.data()).containsEntry("payload_count_estimated", false).containsEntry("estimated", true);
    }
    @Test void adapterIsNeverAppliedToUnrelatedModel() {
        TokenCounter wrong = new TokenCounter() {
            public boolean supports(String model) { return "embedding-only".equals(model); }
            public int priority() { return 100; }
            public Count count(String model, String text) { throw new AssertionError("wrong model tokenizer"); }
        };
        var budget = new ModelContextBudget(json, profile(20_000, 1000), List.of(wrong, new ConservativeTokenCounter()));
        assertThat(budget.inspect("test", request()).countingMethod()).isEqualTo("utf8_byte_estimate");
    }
    @Test void unknownModelWithoutExplicitWindowFailsInsteadOfGuessing() {
        var budget = new ModelContextBudget(json, new MockEnvironment(), List.of(new ConservativeTokenCounter()));
        assertThatThrownBy(() -> budget.inspect("unknown-next-generation", request())).hasMessageContaining("显式配置");
    }
    @Test void explicitlyConfiguredUnknownModelIsSupported() {
        var environment = new MockEnvironment().withProperty("audiorag.llm.context-window", "65536")
                .withProperty("audiorag.llm.output-reserve", "2048");
        var budget = new ModelContextBudget(json, environment, List.of(new ConservativeTokenCounter()));
        var snapshot = budget.inspect("unknown", request());
        assertThat(snapshot.contextWindow()).isEqualTo(65536);
        assertThat(snapshot.outputReserve()).isEqualTo(2048);
        assertThat(snapshot.data()).containsEntry("profile", "configured_window");
    }
    @Test void fixedProfileMustMatchActualModel() {
        assertThatThrownBy(() -> budget(20_000, 1000).inspect("another", request())).hasMessageContaining("不匹配");
    }
    @Test void rejectsInvalidConfiguredWindowOutputReserveAndMargin() {
        assertThatThrownBy(() -> new ModelContextBudget.Profile("test", "p", 100, 100, 0, 0, 0.0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextBudget.Profile("test", "p", 4096, 4096, 0, 0, 0.0, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ModelContextBudget.Profile("test", "p", 4096, 100, 0, 0, Double.NaN, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void metadataAndErrorExposeCountsNotSensitiveMessagesOrSchemaText() {
        Map<String, Object> body = Map.of("messages", List.of(Map.of("content", "PRIVATE_SOURCE_TEXT".repeat(1000))),
                "tools", List.of(Map.of("description", "PRIVATE_TOOL_DESCRIPTION")));
        var snapshot = budget(4096, 1000).inspect("test", body);
        var exception = new ModelContextBudgetException(snapshot);
        String serialized = json.writeValueAsString(exception.metadata());
        assertThat(serialized).doesNotContain("PRIVATE_SOURCE_TEXT", "PRIVATE_TOOL_DESCRIPTION", "content");
        assertThat(exception.getMessage()).contains("估算").doesNotContain("PRIVATE_SOURCE_TEXT");
        assertThat(exception.snapshot()).isSameAs(snapshot);
    }
    @Test void metadataIsReadOnly() {
        var metadata = budget(20_000, 1000).inspect("test", request()).data();
        assertThatThrownBy(() -> metadata.put("content", "secret")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void absentToolsAndEmptyHistoryStillCountRequestOptions() {
        var snapshot = budget(20_000, 1000).inspect("test", Map.of("messages", List.of(), "max_tokens", 1000));
        assertThat(snapshot.inputTokens()).isPositive();
        assertThat(snapshot.requestOptionsTokens()).isPositive();
        assertThat(snapshot.withinBudget()).isTrue();
    }

    private Map<String, Object> request() {
        return Map.of("model", "test", "messages", List.of(
                        Map.of("role", "system", "content", "系统约束"),
                        Map.of("role", "user", "content", "旧问题"),
                        Map.of("role", "tool", "tool_call_id", "id_1", "content", "观察结果")),
                "tools", List.of(Map.of("type", "function", "function", Map.of("name", "read", "description", "读文件"))),
                "max_tokens", 1000, "stream", true, "stream_options", Map.of("include_usage", true));
    }
    private ModelContextBudget budget(long window, long output) {
        return new ModelContextBudget(json, profile(window, output), List.of(new ConservativeTokenCounter()));
    }
    private ModelContextBudget.Profile profile(long window, long output) {
        return new ModelContextBudget.Profile("test", "offline_test", window, output, 256, 128, 0.1, true);
    }
}
