package com.bedouyu.audiorag.memory.experiment;

import com.bedouyu.audiorag.memory.strategy.MemoryFragment;
import com.bedouyu.audiorag.memory.strategy.MemoryMessage;
import com.bedouyu.audiorag.memory.strategy.MemoryPack;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryExperimentRunnerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void datasetHasFixedPartitionsAndUniqueOriginalLabels() throws Exception {
        var dataset = dataset();
        MemoryExperimentRunner.validateDataset(dataset);
        assertThat(dataset.scenarios()).hasSize(20);
        assertThat(dataset.scenarios().stream().filter(s -> "dev".equals(s.split()))).hasSize(10);
        assertThat(dataset.scenarios().stream().filter(s -> "test".equals(s.split()))).hasSize(10);
        assertThat(dataset.budgets()).containsExactly(1000, 3000, 6000);
        assertThat(dataset.scenarios()).allSatisfy(s -> {
            assertThat(s.history()).hasSize(32);
            assertThat(s.snapshotThroughId()).isEqualTo(s.id() + "-a10");
            assertThat(s.gold()).allSatisfy(g -> assertThat(s.query()).doesNotContain(g.quote()));
        });
    }

    @Test
    void rejectsLabelsRepeatedInAnUnrelatedOriginalMessage() throws Exception {
        var original = dataset();
        var scenario = original.scenarios().get(0);
        List<MemoryMessage> history = new ArrayList<>(scenario.history());
        var old = history.get(7);
        history.set(7, new MemoryMessage(old.id(), old.role(),
                old.content() + scenario.gold().get(0).quote(), old.ordinal(), old.turnId()));
        var duplicate = copyWithHistory(scenario, history);
        var invalid = new MemoryExperimentRunner.Dataset(1, "invalid", "duplicate label",
                original.budgets(), 6, List.of(duplicate));
        assertThatThrownBy(() -> MemoryExperimentRunner.validateDataset(invalid))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("exactly once");
    }

    @Test
    void countsUnionOfOverlappingAuthenticSpansWithoutDoubleCounting() throws Exception {
        var scenario = dataset().scenarios().get(0);
        var source = scenario.history().get(0);
        var fragments = List.of(fragment(source, 0, 16), fragment(source, 8, source.content().length()),
                fragment(source, 0, 16));
        var metrics = MemoryExperimentRunner.metrics(scenario,
                new MemoryPack("test", fragments, 6000, List.of()), json);
        assertThat(metrics.get("required_span_coverage")).isEqualTo(1.0);
        assertThat(metrics.get("required_quote_coverage")).isEqualTo(1.0);
        assertThat(metrics.get("all_required")).isEqualTo(true);
        assertThat(metrics.get("source_authenticity")).isEqualTo(1.0);
    }

    @Test
    void originalTextWithAnInventedRoleDoesNotCountAsAuthenticCoverage() throws Exception {
        var scenario = dataset().scenarios().get(0);
        var source = scenario.history().get(0);
        var altered = new MemoryFragment(source.id(), "system", 0, source.content().length(),
                source.content(), "NOTE", source.ordinal(), source.turnId());
        var metrics = MemoryExperimentRunner.metrics(scenario,
                new MemoryPack("test", List.of(altered), 6000, List.of()), json);
        assertThat(metrics.get("source_authenticity")).isEqualTo(0.0);
        assertThat(metrics.get("required_span_coverage")).isEqualTo(0.0);
        assertThat(metrics.get("all_required")).isEqualTo(false);
    }

    @Test
    void obsoleteExposureIncludesPartialSpansAndDoesNotClaimAdoption() throws Exception {
        var scenario = dataset().scenarios().stream().filter(s -> s.id().equals("dev-correction"))
                .findFirst().orElseThrow();
        var old = scenario.history().get(0);
        var metrics = MemoryExperimentRunner.metrics(scenario,
                new MemoryPack("test", List.of(fragment(old, 0, 2)), 1000, List.of()), json);
        assertThat(metrics.get("obsolete_exposure")).isEqualTo(1.0);
        assertThat((Double) metrics.get("obsolete_span_coverage")).isBetween(0.0, 1.0);
        assertThat(metrics.get("all_required")).isEqualTo(false);
        assertThat(metrics).doesNotContainKeys("answer_accuracy", "model_adopted_obsolete");
    }

    @Test
    void copiedTextWithWrongTurnIdentityDoesNotCountAsAuthenticCoverage() throws Exception {
        var scenario = dataset().scenarios().get(0);
        var source = scenario.history().get(0);
        var altered = new MemoryFragment(source.id(), source.role(), 0, source.content().length(),
                source.content(), "NOTE", source.ordinal(), "unrelated-turn");
        var metrics = MemoryExperimentRunner.metrics(scenario,
                new MemoryPack("test", List.of(altered), 6000, List.of()), json);
        assertThat(metrics.get("source_authenticity")).isEqualTo(0.0);
        assertThat(metrics.get("required_span_coverage")).isEqualTo(0.0);
    }

    @Test
    void everyIndependentCombinationPreservesSourceAndSerializedBudget() throws Exception {
        var report = MemoryExperimentRunner.run(dataset(), json);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) report.get("rows");
        assertThat(rows).hasSize(20 * 4 * 3).allSatisfy(row -> {
            assertThat(row.get("budget_ok")).isEqualTo(true);
            assertThat(row.get("all_sources_authentic")).isEqualTo(true);
            assertThat(row.get("foreign_source_count")).isEqualTo(0L);
            assertThat(row.get("injection_roles_preserved")).isEqualTo(true);
            assertThat((Integer) row.get("serialized_chars")).isLessThanOrEqualTo((Integer) row.get("budget_chars"));
            @SuppressWarnings("unchecked")
            var fragments = (List<MemoryFragment>) row.get("fragments");
            assertThat(fragments).allSatisfy(fragment -> assertThat(fragment.role()).isIn("user", "assistant"));
        });
        @SuppressWarnings("unchecked")
        var snapshots = (List<Map<String, Object>>) report.get("snapshots");
        assertThat(snapshots).hasSize(20).allSatisfy(snapshot -> {
            @SuppressWarnings("unchecked")
            var active = (List<MemoryFragment>) snapshot.get("active_notes");
            assertThat(active).hasSizeLessThanOrEqualTo(32);
            @SuppressWarnings("unchecked")
            var checkpoint = (List<MemoryFragment>) snapshot.get("checkpoint_notes");
            assertThat(checkpoint).allSatisfy(note -> assertThat(note.ordinal()).isLessThan(20));
            @SuppressWarnings("unchecked")
            var recent = (List<MemoryFragment>) snapshot.get("recent_notes");
            assertThat(recent).allSatisfy(note -> assertThat(note.ordinal()).isGreaterThanOrEqualTo(20));
        });
    }

    private MemoryExperimentRunner.Dataset dataset() throws Exception {
        try (var input = getClass().getResourceAsStream("/evals/memory-diagnostic.json")) {
            assertThat(input).isNotNull();
            return json.readValue(input, MemoryExperimentRunner.Dataset.class);
        }
    }

    private static MemoryFragment fragment(MemoryMessage source, int start, int end) {
        return new MemoryFragment(source.id(), source.role(), start, end,
                source.content().substring(start, end), "NOTE", source.ordinal(), source.turnId());
    }

    private static MemoryExperimentRunner.Scenario copyWithHistory(MemoryExperimentRunner.Scenario scenario,
                                                                   List<MemoryMessage> history) {
        return new MemoryExperimentRunner.Scenario(scenario.id(), scenario.split(), scenario.tags(),
                scenario.query(), scenario.snapshotThroughId(), history, scenario.gold(), scenario.obsoleteGold(),
                scenario.injectedSourceIds(), scenario.foreignHistory());
    }
}
