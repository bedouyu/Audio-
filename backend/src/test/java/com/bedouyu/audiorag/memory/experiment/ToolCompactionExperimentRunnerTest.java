package com.bedouyu.audiorag.memory.experiment;

import com.bedouyu.audiorag.core.loop.AgentTranscript;
import com.bedouyu.audiorag.model.dto.AgentModelReply;
import com.bedouyu.audiorag.model.dto.FunctionCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCompactionExperimentRunnerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void fixedReplayPreservesProtocolAndSeparatesStatusAndReferenceRetentionFromJsonValidity() {
        Map<String, Object> report = ToolCompactionExperimentRunner.run(json);
        List<Map<String, Object>> rows = rows(report);
        assertThat(rows).hasSize(16);
        for (Map<String, Object> row : rows) {
            assertThat(((Number) row.get("input_context_chars")).intValue()).isGreaterThan(48000);
            assertThat(((Number) row.get("protocol_chars_without_observation_content")).intValue()).isLessThan(3000);
            if (row.get("outcome").equals("LIMIT_REACHED")) continue;
            assertThat(row).containsEntry("old_json_valid_ratio", 1.0).containsEntry("context_within_budget", true)
                    .containsEntry("protocol_call_id_pairing_preserved", true).containsEntry("protocol_frames_preserved", true)
                    .containsEntry("reasoning_preserved", true).containsEntry("latest_round_preserved", true)
                    .containsEntry("invented_reference_count", 0);
            assertThat(row.get("latest_round_sha256_after")).isEqualTo(row.get("latest_round_sha256_before"));
            assertThat(((Number) row.get("old_body_reduction_ratio")).doubleValue()).isGreaterThan(.98);
            if (row.get("strategy").equals("preview")) {
                assertThat(row).containsEntry("status_exact_ratio", 0.0).containsEntry("old_preview_prefix_count", 1)
                        .containsEntry("old_preview_prefix_json_valid_count", 0);
                for (String kind : List.of("artifact", "citation", "source"))
                    assertThat(((Number) row.get(kind + "_reference_retained")).longValue()).isZero();
            } else {
                assertThat(row).containsEntry("status_exact_ratio", 1.0).containsEntry("old_preview_prefix_count", 0);
                for (String kind : List.of("artifact", "citation", "source"))
                    assertThat(row.get(kind + "_reference_retained")).isEqualTo(row.get(kind + "_reference_total"));
            }
        }
    }

    @Test
    void previewStringsAndCompactedStatusNeverPretendToRetainOriginalStructuralReferences() {
        var fixture = ToolCompactionExperimentRunner.fixtures(json).get(2);
        var output = new ArrayList<>(fixture.originalMessages());
        int oldIndex = fixture.initial().size() + 1;
        var old = new LinkedHashMap<>(output.get(oldIndex));
        old.put("content", json.writeValueAsString(Map.of("status", "COMPACTED", "preview",
                "{\"artifact_id\":\"artifact-fixture-003\",\"source_id\":\"source-fixture-003\",\"status\":\"PENDING_REVIEW\"}")));
        output.set(oldIndex, old);
        var metrics = ToolCompactionExperimentRunner.metrics(fixture, output, json);
        assertThat(metrics).containsEntry("status_exact_ratio", 0.0).containsEntry("artifact_reference_retained", 0L)
                .containsEntry("source_reference_retained", 0L).containsEntry("invented_reference_count", 0);
        old.put("content", json.writeValueAsString(Map.of("status", "COMPACTED", "preview",
                Map.of("artifact_id", "artifact-fixture-003", "source_id", "source-fixture-003"))));
        metrics = ToolCompactionExperimentRunner.metrics(fixture, output, json);
        assertThat(metrics).containsEntry("source_reference_retained", 0L).containsEntry("artifact_reference_retained", 0L);
        old.put("content", json.writeValueAsString(Map.of("status", "PENDING_REVIEW", "source_id", "fabricated-source",
                "content", "source-fixture-003 artifact-fixture-003")));
        metrics = ToolCompactionExperimentRunner.metrics(fixture, output, json);
        assertThat(metrics).containsEntry("source_reference_retained", 0L).containsEntry("artifact_reference_retained", 0L)
                .containsEntry("invented_reference_count", 1);
    }

    @Test
    void protocolReasoningAndLatestRoundChecksDetectChangedFrames() {
        var fixture = ToolCompactionExperimentRunner.fixtures(json).get(0);
        var output = new ArrayList<>(fixture.originalMessages());
        int oldIndex = fixture.initial().size() + 1;
        var wrongTool = new LinkedHashMap<>(output.get(oldIndex));
        wrongTool.put("tool_call_id", "wrong-call-id");
        output.set(oldIndex, wrongTool);
        assertThat(ToolCompactionExperimentRunner.protocolPaired(output)).isFalse();
        var metrics = ToolCompactionExperimentRunner.metrics(fixture, output, json);
        assertThat(metrics).containsEntry("protocol_call_id_pairing_preserved", false).containsEntry("protocol_frames_preserved", false);
        output = new ArrayList<>(fixture.originalMessages());
        var changedReasoning = new LinkedHashMap<>(output.get(fixture.initial().size()));
        changedReasoning.put("reasoning_content", "truncated");
        output.set(fixture.initial().size(), changedReasoning);
        var changedLatest = new LinkedHashMap<>(output.get(output.size() - 1));
        changedLatest.put("content", "changed latest observation");
        output.set(output.size() - 1, changedLatest);
        metrics = ToolCompactionExperimentRunner.metrics(fixture, output, json);
        assertThat(metrics).containsEntry("reasoning_preserved", false).containsEntry("latest_round_preserved", false);
    }

    @Test
    void multipleObservationsKeepBothCallPairsAndUseAllOriginalReferencesInTheDenominator() {
        var base = ToolCompactionExperimentRunner.fixtures(json).get(0);
        String firstId = String.valueOf(base.oldObservations().get(0).get("tool_call_id"));
        String secondId = "second-old-call";
        Map<String, Object> assistant = new AgentModelReply("", "complete synthetic multi-call reasoning",
                List.of(new FunctionCall(firstId, "offline_fixture_probe", "{}"),
                        new FunctionCall(secondId, "offline_fixture_probe", "{}")), "tool_calls").assistantMessage();
        Map<String, Object> secondPayload = new LinkedHashMap<>();
        secondPayload.put("content", "SECOND SYNTHETIC BODY ; ".repeat(2500));
        secondPayload.put("status", "PENDING_REVIEW");
        secondPayload.put("artifact_id", "second-original-artifact");
        secondPayload.put("source_id", "second-original-source");
        var fixture = new ToolCompactionExperimentRunner.Fixture("multi", base.initial(), assistant,
                List.of(base.oldObservations().get(0), Map.of("role", "tool", "tool_call_id", secondId,
                        "content", json.writeValueAsString(secondPayload))), base.latestAssistant(), base.latestObservations());
        var transcript = new AgentTranscript(fixture.initial(), json, "structured");
        transcript.addRound(fixture.oldAssistant(), fixture.oldObservations());
        transcript.addRound(fixture.latestAssistant(), fixture.latestObservations());
        var metrics = ToolCompactionExperimentRunner.metrics(fixture, transcript.messages(), json);
        assertThat(metrics).containsEntry("old_observation_count", 2).containsEntry("old_json_valid_count", 2)
                .containsEntry("protocol_call_id_pairing_preserved", true).containsEntry("protocol_frames_preserved", true)
                .containsEntry("reasoning_preserved", true).containsEntry("latest_round_preserved", true)
                .containsEntry("artifact_reference_total", 2L).containsEntry("artifact_reference_retained", 2L)
                .containsEntry("source_reference_total", 1L).containsEntry("source_reference_retained", 1L);
    }

    @Test
    void explicitLimitDoesNotFabricateAnObservableContextOrSuccessfulRetention() {
        var report = ToolCompactionExperimentRunner.run(json);
        List<Map<String, Object>> limited = rows(report).stream().filter(row -> row.get("outcome").equals("LIMIT_REACHED")).toList();
        assertThat(limited).hasSize(2).allSatisfy(row -> {
            assertThat(row).containsEntry("explicit_limit", true).containsEntry("fixture_id", "tool-fixture-08");
            assertThat(row.get("output_context_chars")).isNull();
            assertThat(row.get("protocol_frames_preserved")).isNull();
            assertThat(row.get("latest_round_preserved")).isNull();
            assertThat(row.get("source_reference_retained")).isNull();
        });
        @SuppressWarnings("unchecked") var summaries = (List<Map<String, Object>>) report.get("summary");
        assertThat(summaries).hasSize(2).allSatisfy(summary -> {
            assertThat(summary).containsEntry("packed_runs", 7).containsEntry("explicit_limit_runs", 1)
                    .containsEntry("observable_reference_denominator_runs", 7);
        });
    }

    @Test
    void referenceIdsAreAfterTheLargeBodyAndReplayRowsAreDeterministic() {
        for (var fixture : ToolCompactionExperimentRunner.fixtures(json)) {
            String raw = String.valueOf(fixture.oldObservations().get(0).get("content"));
            var parsed = json.readTree(raw);
            String body = parsed.path("content").asText();
            assertThat(body.length()).isGreaterThan(48000);
            assertThat(raw.indexOf("\"status\"")).isGreaterThan(raw.indexOf(body) + body.length());
            assertThat(ToolCompactionExperimentRunner.protocolPaired(fixture.originalMessages())).isTrue();
        }
        assertThat(rows(ToolCompactionExperimentRunner.run(json))).isEqualTo(rows(ToolCompactionExperimentRunner.run(json)));
    }

    @Test
    void commandLineWritesNonCollidingJsonCsvAndAnExplicitClaimBoundary(@TempDir Path output) throws Exception {
        Files.writeString(output.resolve("report.json"), "existing conversation-memory report");
        ToolCompactionExperimentRunner.main(new String[]{"--output-dir", output.toString()});
        assertThat(Files.readString(output.resolve("report.json"))).isEqualTo("existing conversation-memory report");
        assertThat(json.readTree(Files.readString(output.resolve("tool-compaction-report.json"))).path("strategy_run_count").asInt()).isEqualTo(16);
        assertThat(Files.readString(output.resolve("tool-compaction-results.csv"))).contains("status_exact_ratio", "LIMIT_REACHED");
        assertThat(Files.readString(output.resolve("tool-compaction-summary.csv"))).contains("observable_reference_denominator_runs");
        assertThat(Files.readString(output.resolve("tool-compaction-README.md"))).contains("no actual tool", "LLM answer accuracy");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> report) { return (List<Map<String, Object>>) report.get("rows"); }
}
