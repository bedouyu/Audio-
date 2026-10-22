package com.bedouyu.audiorag.memory.experiment;

import com.bedouyu.audiorag.memory.strategy.MemoryFragment;
import com.bedouyu.audiorag.memory.strategy.MemoryMessage;
import com.bedouyu.audiorag.memory.strategy.MemoryPack;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Local objective parsing and packing tests only. No API key reads or network/model calls. */
class MemoryModelEvaluationRunnerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void originalVisibleQuoteWithItsSourceRecoversGold() throws Exception {
        var scenario = scenario();
        var gold = scenario.gold().get(0);
        var result = evaluate(quoteJson(gold.sourceId(), gold.quote()), fullPack(scenario), scenario);
        assertThat(result.get("status")).isEqualTo("OK");
        assertThat(result.get("supported_quote_ratio")).isEqualTo(1.0);
        assertThat(result.get("gold_recovery")).isEqualTo(1.0);
        assertThat(result.get("all_gold_recovered")).isEqualTo(true);
    }

    @Test
    void missingSourceIsSchemaFailureAndForgedSourceIsUnsupported() throws Exception {
        var scenario = scenario();
        var gold = scenario.gold().get(0);
        String missing = json.writeValueAsString(Map.of("quotes", List.of(Map.of("quote", gold.quote()))));
        var invalid = evaluate(missing, fullPack(scenario), scenario);
        assertThat(invalid.get("status")).isEqualTo("QUOTE_JSON_INVALID");
        assertThat(invalid.get("gold_recovery")).isNull();
        var forged = evaluate(quoteJson("fabricated-source", gold.quote()), fullPack(scenario), scenario);
        assertThat(forged.get("status")).isEqualTo("OK");
        assertThat(forged.get("supported_quote_ratio")).isEqualTo(0.0);
        assertThat(forged.get("gold_recovery")).isEqualTo(0.0);
    }

    @Test
    void originalHistoryTextOmittedFromSelectedPackDoesNotCountAsSupport() throws Exception {
        var scenario = scenario();
        var gold = scenario.gold().get(0);
        var source = source(scenario, gold.sourceId());
        var selected = new MemoryPack("recent_window", List.of(fragment(source, 0, 2)), 3000, List.of());
        var result = evaluate(quoteJson(gold.sourceId(), gold.quote()), selected, scenario);
        assertThat(source.content()).contains(gold.quote());
        assertThat(result.get("supported_quote_ratio")).isEqualTo(0.0);
        assertThat(result.get("gold_recovery")).isEqualTo(0.0);
    }

    @Test
    void supportedPartialQuoteDoesNotRecoverTheWholeGoldLabel() throws Exception {
        var scenario = scenario();
        var gold = scenario.gold().get(0);
        var result = evaluate(quoteJson(gold.sourceId(), gold.quote().substring(0, 4)), fullPack(scenario), scenario);
        assertThat(result.get("supported_quote_ratio")).isEqualTo(1.0);
        assertThat(result.get("gold_recovery")).isEqualTo(0.0);
    }

    @Test
    void adjacentFragmentsAreNotSilentlyJoinedToSupportAQuote() throws Exception {
        var scenario = scenario();
        var gold = scenario.gold().get(0);
        var source = source(scenario, gold.sourceId());
        int middle = source.content().length() / 2;
        var selected = new MemoryPack("hybrid", List.of(fragment(source, 0, middle),
                fragment(source, middle, source.content().length())), 3000, List.of());
        var result = evaluate(quoteJson(gold.sourceId(), gold.quote()), selected, scenario);
        assertThat(result.get("supported_quote_ratio")).isEqualTo(0.0);
    }

    @Test
    void validEmptyQuotesHaveNoSupportDenominatorAndZeroRecovery() throws Exception {
        var scenario = scenario();
        var result = evaluate("{\"quotes\":[]}", fullPack(scenario), scenario);
        assertThat(result.get("status")).isEqualTo("OK");
        assertThat(result.get("supported_quote_ratio")).isNull();
        assertThat(result.get("quote_count")).isEqualTo(0);
        assertThat(result.get("gold_recovery")).isEqualTo(0.0);
        assertThat(result.get("all_gold_recovered")).isEqualTo(false);
    }

    @Test
    void duplicateReturnedQuotesCannotRecoverAGoldLabelTwice() throws Exception {
        var scenario = scenario();
        var gold = scenario.gold().get(0);
        var quote = Map.of("source_id", gold.sourceId(), "quote", gold.quote());
        var result = evaluate(json.writeValueAsString(Map.of("quotes", List.of(quote, quote))),
                fullPack(scenario), scenario);
        assertThat(result.get("quote_count")).isEqualTo(2);
        assertThat(result.get("recovered_gold_count")).isEqualTo(1);
        assertThat(result.get("gold_recovery")).isEqualTo(1.0);
    }

    @Test
    void providerLengthAndInvalidJsonRemainDistinctAndUsageIsRecorded() throws Exception {
        var scenario = scenario();
        var trial = new MemoryModelEvaluationRunner.Trial(1, scenario, fullPack(scenario));
        String body = providerBody("{\"quotes\":[]}", "length");
        var length = MemoryModelEvaluationRunner.parseProviderResponse(200, body, trial, json, 12.5);
        assertThat(length.get("status")).isEqualTo("LENGTH");
        assertThat(length.get("gold_recovery")).isNull();
        assertThat(length.get("response_content")).isEqualTo("{\"quotes\":[]}");
        assertThat(length.get("usage")).isEqualTo(Map.of("prompt_tokens", 55L,
                "completion_tokens", 12L, "total_tokens", 67L));
        var responseInvalid = MemoryModelEvaluationRunner.parseProviderResponse(200, "bad{", trial, json, 2);
        assertThat(responseInvalid.get("status")).isEqualTo("RESPONSE_JSON_INVALID");
        var quoteInvalid = MemoryModelEvaluationRunner.parseProviderResponse(200,
                providerBody("not quote JSON", "stop"), trial, json, 2);
        assertThat(quoteInvalid.get("status")).isEqualTo("QUOTE_JSON_INVALID");
        assertThat(quoteInvalid.get("response_content")).isEqualTo("not quote JSON");
    }

    @Test
    void providerErrorBodiesAndTheirPotentialCredentialsAreNotSaved() throws Exception {
        var scenario = scenario();
        var trial = new MemoryModelEvaluationRunner.Trial(1, scenario, fullPack(scenario));
        String sensitive = "{\"error\":{\"message\":\"key sk-private-example failed\"}}";
        var result = MemoryModelEvaluationRunner.parseProviderResponse(401, sensitive, trial, json, 2);
        assertThat(result.get("status")).isEqualTo("HTTP_ERROR");
        assertThat(result.get("http_status")).isEqualTo(401);
        assertThat(result.get("response_content")).isNull();
        assertThat(json.writeValueAsString(result)).doesNotContain("sk-private-example", "failed");
        var errorInSuccess = MemoryModelEvaluationRunner.parseProviderResponse(200, sensitive, trial, json, 2);
        assertThat(errorInSuccess.get("status")).isEqualTo("INVALID_PROVIDER_RESPONSE");
        assertThat(json.writeValueAsString(errorInSuccess)).doesNotContain("sk-private-example");
    }

    @Test
    void unicodeEncodedCredentialsInProviderContentOrNestedQuotesAreSuppressed() throws Exception {
        var scenario = scenario();
        var trial = new MemoryModelEvaluationRunner.Trial(1, scenario, fullPack(scenario));
        String fakeKey = "sk-fake-local-only";
        String unicodeKey = fakeKey.chars().mapToObj(character -> "\\" + "u" + String.format("%04x", character))
                .reduce("", String::concat);
        String envelope = providerBody(fakeKey, "stop").replace(fakeKey, unicodeKey);
        assertThat(envelope).doesNotContain(fakeKey);
        var decodedContent = MemoryModelEvaluationRunner.guardedProviderResponse(200, envelope, trial, json, 1, fakeKey);
        assertThat(decodedContent.get("status")).isEqualTo("SENSITIVE_RESPONSE");
        assertThat(decodedContent.get("response_content")).isNull();
        assertThat(json.writeValueAsString(decodedContent)).doesNotContain(fakeKey);

        String nestedQuote = "{\"quotes\":[{\"source_id\":\"fake-source\",\"quote\":\"" + unicodeKey + "\"}]}";
        var decodedQuote = MemoryModelEvaluationRunner.guardedProviderResponse(200,
                providerBody(nestedQuote, "stop"), trial, json, 1, fakeKey);
        assertThat(decodedQuote.get("status")).isEqualTo("SENSITIVE_RESPONSE");
        assertThat(decodedQuote.get("quotes")).isEqualTo(List.of());
        assertThat(json.writeValueAsString(decodedQuote)).doesNotContain(fakeKey);
        for (String invalidSchema : List.of("{\"error\":\"" + unicodeKey + "\"}",
                "{\"quotes\":[],\"extra\":\"" + unicodeKey + "\"}", "not valid JSON " + unicodeKey)) {
            var rejected = MemoryModelEvaluationRunner.guardedProviderResponse(200,
                    providerBody(invalidSchema, "stop"), trial, json, 1, fakeKey);
            assertThat(rejected.get("status")).isEqualTo("SENSITIVE_RESPONSE");
            assertThat(rejected.get("response_content")).isNull();
            assertThat(json.writeValueAsString(rejected)).doesNotContain(fakeKey, unicodeKey);
        }
    }

    @Test
    void requestIncludesOnlyCurrentQueryAndSelectedMemoryAsUserData() throws Exception {
        var scenario = scenario();
        var gold = scenario.gold().get(0);
        var source = source(scenario, gold.sourceId());
        var selected = new MemoryPack("hybrid", List.of(fragment(source, 0, 2)), 3000, List.of());
        var trial = new MemoryModelEvaluationRunner.Trial(1, scenario, selected);
        var request = MemoryModelEvaluationRunner.requestBody(trial, "test-model", json);
        var messages = json.valueToTree(request.get("messages"));
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.path(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.path(1).path("role").asText()).isEqualTo("user");
        var visible = json.readTree(messages.path(1).path("content").asText());
        assertThat(visible.size()).isEqualTo(2);
        assertThat(visible.path("current_query").asText()).isEqualTo(scenario.query());
        assertThat(visible.has("visible_memory")).isTrue();
        assertThat(messages.path(1).path("content").asText()).doesNotContain(gold.quote(),
                "obsoleteGold", "foreignHistory", "\"gold\"", "\"history\"");
        assertThat(request.get("stream")).isEqualTo(false);
        assertThat(request.get("max_tokens")).isEqualTo(1200);
        assertThat(request).doesNotContainKeys("tools", "tool_choice");
    }

    @Test
    void callPlanPairsSameTwoFixedTestScenariosAcrossEveryStrategy() throws Exception {
        var dataset = dataset();
        var trials = MemoryModelEvaluationRunner.plan(dataset, 3000, 8, json);
        assertThat(trials).hasSize(8);
        var expected = dataset.scenarios().stream().filter(s -> s.split().equals("test")).limit(2).toList();
        for (var scenario : expected) {
            assertThat(trials.stream().filter(t -> t.scenario().id().equals(scenario.id())).map(t -> t.pack().strategy()))
                    .containsExactlyInAnyOrderElementsOf(MemoryExperimentRunner.STRATEGIES);
        }
        assertThat(trials).allSatisfy(trial -> {
            assertThat(trial.scenario().split()).isEqualTo("test");
            assertThat(trial.pack().characters(json)).isLessThanOrEqualTo(3000);
        });
    }

    @Test
    void paidCallLimitOnlyAllowsCompleteFourStrategyGroups() {
        for (int invalid : List.of(0, 3, 5, 9, 41, 44)) {
            assertThatThrownBy(() -> MemoryModelEvaluationRunner.validateLimits(3000, invalid))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("multiple of four");
        }
        assertThatCode(() -> MemoryModelEvaluationRunner.validateLimits(3000, 4)).doesNotThrowAnyException();
        assertThatCode(() -> MemoryModelEvaluationRunner.validateLimits(3000, 40)).doesNotThrowAnyException();
    }

    private Map<String, Object> evaluate(String content, MemoryPack pack, MemoryExperimentRunner.Scenario scenario) {
        return MemoryModelEvaluationRunner.evaluateQuotes(content, pack, scenario, json);
    }

    private String quoteJson(String sourceId, String quote) {
        return json.writeValueAsString(Map.of("quotes", List.of(Map.of("source_id", sourceId, "quote", quote))));
    }

    private String providerBody(String content, String finish) {
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", content),
                "finish_reason", finish)), "usage", Map.of("prompt_tokens", 55,
                "completion_tokens", 12, "total_tokens", 67)));
    }

    private MemoryExperimentRunner.Scenario scenario() throws Exception {
        return dataset().scenarios().stream().filter(s -> s.split().equals("test")).findFirst().orElseThrow();
    }

    private MemoryExperimentRunner.Dataset dataset() throws Exception {
        try (var input = getClass().getResourceAsStream("/evals/memory-diagnostic.json")) {
            assertThat(input).isNotNull();
            return json.readValue(input, MemoryExperimentRunner.Dataset.class);
        }
    }

    private static MemoryMessage source(MemoryExperimentRunner.Scenario scenario, String id) {
        return scenario.history().stream().filter(source -> source.id().equals(id)).findFirst().orElseThrow();
    }

    private static MemoryPack fullPack(MemoryExperimentRunner.Scenario scenario) {
        var original = source(scenario, scenario.gold().get(0).sourceId());
        return new MemoryPack("hybrid", List.of(fragment(original, 0, original.content().length())), 3000, List.of());
    }

    private static MemoryFragment fragment(MemoryMessage original, int start, int end) {
        return new MemoryFragment(original.id(), original.role(), start, end,
                original.content().substring(start, end), "NOTE", original.ordinal(), original.turnId());
    }
}
