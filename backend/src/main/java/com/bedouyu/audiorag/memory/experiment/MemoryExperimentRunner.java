package com.bedouyu.audiorag.memory.experiment;

import com.bedouyu.audiorag.memory.strategy.MemoryFragment;
import com.bedouyu.audiorag.memory.strategy.MemoryMessage;
import com.bedouyu.audiorag.memory.strategy.MemoryPack;
import com.bedouyu.audiorag.memory.strategy.MemoryStrategyEngine;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Source-retention diagnostic only: no Spring, database, network or model calls.
 * Gold quotes are independently validated original spans and never enter a strategy.
 */
public final class MemoryExperimentRunner {
    public static final List<String> STRATEGIES =
            List.of("recent_window", "query_recall", "extractive_notes", "hybrid");
    private static final int WARMUP_RUNS = 2;
    private static final int TIMED_RUNS = 7;

    public record Gold(String sourceId, String quote, String kind) { }

    public record Scenario(String id, String split, List<String> tags, String query,
                           String snapshotThroughId, List<MemoryMessage> history,
                           List<Gold> gold, List<Gold> obsoleteGold,
                           List<String> injectedSourceIds, List<MemoryMessage> foreignHistory) {
        public Scenario {
            tags = tags == null ? List.of() : List.copyOf(tags);
            history = history == null ? List.of() : List.copyOf(history);
            gold = gold == null ? List.of() : List.copyOf(gold);
            obsoleteGold = obsoleteGold == null ? List.of() : List.copyOf(obsoleteGold);
            injectedSourceIds = injectedSourceIds == null ? List.of() : List.copyOf(injectedSourceIds);
            foreignHistory = foreignHistory == null ? List.of() : List.copyOf(foreignHistory);
        }
    }

    public record Dataset(int schemaVersion, String datasetId, String description,
                          List<Integer> budgets, int checkpointRecentRounds,
                          List<Scenario> scenarios) { }

    record Span(String sourceId, int start, int end) { }

    private MemoryExperimentRunner() { }

    public static void main(String[] arguments) throws Exception {
        Map<String, String> options = parseOptions(arguments);
        ObjectMapper json = new ObjectMapper();
        String raw;
        if (options.containsKey("dataset")) {
            raw = Files.readString(Path.of(options.get("dataset")), StandardCharsets.UTF_8);
        } else {
            try (var input = MemoryExperimentRunner.class.getResourceAsStream("/evals/memory-diagnostic.json")) {
                if (input == null) throw new IllegalStateException("Missing /evals/memory-diagnostic.json");
                raw = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        Dataset dataset = json.readValue(raw, Dataset.class);
        validateDataset(dataset);
        Map<String, Object> report = run(dataset, json);
        report.put("dataset_sha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))));
        Path output = Path.of(options.getOrDefault("output-dir",
                ".cache/memory-experiments/" + System.currentTimeMillis())).toAbsolutePath().normalize();
        Files.createDirectories(output);
        Files.writeString(output.resolve("report.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) report.get("rows");
        Files.writeString(output.resolve("results.csv"), csv(rows), StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        var summaries = (List<Map<String, Object>>) report.get("summary");
        Files.writeString(output.resolve("summary.csv"), csv(summaries), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("README.md"), readme(dataset), StandardCharsets.UTF_8);
        System.out.println("Memory diagnostic: " + dataset.scenarios().size() + " scenarios, "
                + rows.size() + " independent combinations. Report: " + output);
    }

    public static void validateDataset(Dataset dataset) {
        if (dataset == null || dataset.schemaVersion() != 1
                || dataset.datasetId() == null || dataset.datasetId().isBlank()
                || dataset.scenarios() == null || dataset.scenarios().isEmpty()
                || dataset.checkpointRecentRounds() < 1) {
            throw new IllegalArgumentException("Invalid dataset metadata");
        }
        if (dataset.budgets() == null || dataset.budgets().isEmpty()
                || dataset.budgets().stream().anyMatch(b -> b == null || b < 256)
                || new HashSet<>(dataset.budgets()).size() != dataset.budgets().size()) {
            throw new IllegalArgumentException("Budgets must be distinct positive serialized character limits");
        }
        Set<String> scenarioIds = new HashSet<>();
        for (Scenario scenario : dataset.scenarios()) {
            if (scenario.id() == null || scenario.id().isBlank() || !scenarioIds.add(scenario.id())
                    || !List.of("dev", "test").contains(scenario.split())
                    || scenario.query() == null || scenario.query().isBlank()
                    || scenario.gold().isEmpty()) {
                throw new IllegalArgumentException("Invalid scenario: " + scenario.id());
            }
            int messages = scenario.history().size();
            if (messages < 24 || messages > 48 || messages % 2 != 0) {
                throw new IllegalArgumentException("History must have 12-24 complete rounds: " + scenario.id());
            }
            Map<String, MemoryMessage> history = historyById(scenario);
            Set<String> turnIds = new HashSet<>();
            for (int i = 0; i < messages; i++) {
                MemoryMessage message = scenario.history().get(i);
                String expectedRole = i % 2 == 0 ? "user" : "assistant";
                if (!expectedRole.equals(message.role()) || message.ordinal() != i
                        || message.content() == null || message.content().isBlank()) {
                    throw new IllegalArgumentException("History role/order/content invalid: " + message.id());
                }
                if (i % 2 == 0) {
                    String turnId = message.turnId();
                    if (!Objects.equals(turnId, scenario.history().get(i + 1).turnId())
                            || (turnId != null && !turnIds.add(turnId))) {
                        throw new IllegalArgumentException("Complete rounds need distinct shared turn identities");
                    }
                }
            }
            int prefixMessages = messages - dataset.checkpointRecentRounds() * 2;
            if (prefixMessages < 2
                    || !scenario.history().get(prefixMessages - 1).id().equals(scenario.snapshotThroughId())) {
                throw new IllegalArgumentException("Checkpoint must end before fixed recent rounds: " + scenario.id());
            }
            List<Span> required = labels(scenario, scenario.gold());
            List<Span> obsolete = labels(scenario, scenario.obsoleteGold());
            for (Span span : obsolete) {
                if (required.stream().anyMatch(g -> g.sourceId().equals(span.sourceId())
                        && g.start() < span.end() && span.start() < g.end())) {
                    throw new IllegalArgumentException("Required and obsolete labels overlap: " + scenario.id());
                }
            }
            for (String source : scenario.injectedSourceIds()) {
                if (!history.containsKey(source)) throw new IllegalArgumentException("Unknown injection source");
            }
            Set<String> foreignIds = new HashSet<>();
            for (MemoryMessage message : scenario.foreignHistory()) {
                if (message.id() == null || !foreignIds.add(message.id()) || history.containsKey(message.id())) {
                    throw new IllegalArgumentException("Foreign history must have separate source IDs");
                }
            }
        }
    }

    public static Map<String, Object> run(Dataset dataset, ObjectMapper json) {
        validateDataset(dataset);
        MemoryStrategyEngine engine = new MemoryStrategyEngine(json);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> snapshots = new ArrayList<>();
        // Unrelated warmup reduces class-loading noise without using a quality sample.
        List<MemoryMessage> warmup = List.of(new MemoryMessage("warm-u", "user", "目录配色观察。", 0, "warm-turn"),
                new MemoryMessage("warm-a", "assistant", "已登记展示信息。", 1, "warm-turn"));
        for (String strategy : STRATEGIES) engine.pack(strategy, warmup, List.of(), "查看页面", 1000);
        for (Scenario scenario : dataset.scenarios()) {
            int prefixMessages = scenario.history().size() - dataset.checkpointRecentRounds() * 2;
            List<MemoryMessage> prefix = List.copyOf(scenario.history().subList(0, prefixMessages));
            List<MemoryMessage> suffix = List.copyOf(scenario.history().subList(prefixMessages, scenario.history().size()));
            long extractionStart = System.nanoTime();
            List<MemoryFragment> checkpoint = List.copyOf(engine.extractNotes(prefix));
            List<MemoryFragment> recentNotes = List.copyOf(engine.extractNotes(suffix));
            List<MemoryFragment> notes = mergeNotes(checkpoint, recentNotes);
            double extractionMs = elapsedMs(extractionStart);
            assertNotesFromPrefix(prefix, checkpoint);
            assertNotesFromPrefix(suffix, recentNotes);
            snapshots.add(Map.of("scenario_id", scenario.id(), "snapshot_through_id", scenario.snapshotThroughId(),
                    "prefix_messages", prefixMessages, "checkpoint_notes", checkpoint,
                    "recent_notes", recentNotes, "active_notes", notes, "extract_and_merge_ms", extractionMs));
            for (int budget : dataset.budgets()) {
                for (String strategy : STRATEGIES) {
                    for (int i = 0; i < WARMUP_RUNS; i++) {
                        engine.pack(strategy, scenario.history(), notes, scenario.query(), budget);
                    }
                    List<Double> timings = new ArrayList<>();
                    MemoryPack packed = null;
                    for (int i = 0; i < TIMED_RUNS; i++) {
                        long start = System.nanoTime();
                        packed = engine.pack(strategy, scenario.history(), notes, scenario.query(), budget);
                        timings.add(elapsedMs(start));
                    }
                    Map<String, Object> row = metrics(scenario, packed, json);
                    row.put("scenario_id", scenario.id());
                    row.put("split", scenario.split());
                    row.put("tags", scenario.tags());
                    row.put("query", scenario.query());
                    row.put("strategy", strategy);
                    row.put("budget_chars", budget);
                    row.put("history_chars", scenario.history().stream().mapToInt(m -> m.content().length()).sum());
                    row.put("snapshot_through_id", scenario.snapshotThroughId());
                    row.put("checkpoint_notes", checkpoint.size());
                    row.put("recent_notes", recentNotes.size());
                    row.put("active_notes", notes.size());
                    row.put("notes_extract_and_merge_ms", extractionMs);
                    row.put("pack_p50_ms", percentile(timings, .50));
                    row.put("pack_p95_ms", percentile(timings, .95));
                    row.put("pack_samples_ms", timings);
                    row.put("warnings", packed.warnings());
                    row.put("fragments", packed.fragments());
                    row.put("model_data", packed.modelData());
                    rows.add(row);
                }
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("created_at", Instant.now().toString());
        report.put("dataset_id", dataset.datasetId());
        report.put("description", dataset.description());
        report.put("java_version", System.getProperty("java.version"));
        report.put("strategies", STRATEGIES);
        report.put("budgets_chars", dataset.budgets());
        report.put("checkpoint_recent_rounds", dataset.checkpointRecentRounds());
        report.put("timing_samples_per_combination", TIMED_RUNS);
        report.put("scope", "Deterministic source retention, not answer accuracy or live repository isolation");
        report.put("snapshots", snapshots);
        report.put("summary", summaries(rows, false));
        report.put("by_tag", summaries(rows, true));
        report.put("rows", rows);
        return report;
    }

    public static Map<String, Object> metrics(Scenario scenario, MemoryPack pack, ObjectMapper json) {
        Map<String, MemoryMessage> history = historyById(scenario);
        List<MemoryFragment> authentic = pack.fragments().stream()
                .filter(fragment -> authentic(fragment, history)).toList();
        List<Span> required = labels(scenario, scenario.gold());
        List<Span> obsolete = labels(scenario, scenario.obsoleteGold());
        Set<String> requiredSources = new HashSet<>();
        required.forEach(span -> requiredSources.add(span.sourceId()));
        long sourcesCovered = requiredSources.stream()
                .filter(id -> authentic.stream().anyMatch(f -> f.sourceId().equals(id))).count();
        long fullyCovered = required.stream().filter(span -> covered(span, authentic) == span.end() - span.start()).count();
        long requiredCharacters = required.stream().mapToLong(span -> span.end() - span.start()).sum();
        long coveredCharacters = required.stream().mapToLong(span -> covered(span, authentic)).sum();
        long obsoleteExposed = obsolete.stream().filter(span -> covered(span, authentic) > 0).count();
        long obsoleteCharacters = obsolete.stream().mapToLong(span -> span.end() - span.start()).sum();
        long obsoleteCoveredCharacters = obsolete.stream().mapToLong(span -> covered(span, authentic)).sum();
        Set<String> foreignIds = new HashSet<>();
        scenario.foreignHistory().forEach(message -> foreignIds.add(message.id()));
        int serialized = json.writeValueAsString(pack.modelData()).length();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("required_source_coverage", ratio(sourcesCovered, requiredSources.size()));
        metrics.put("required_span_coverage", ratio(coveredCharacters, requiredCharacters));
        metrics.put("required_quote_coverage", ratio(fullyCovered, required.size()));
        metrics.put("all_required", fullyCovered == required.size());
        metrics.put("required_quotes", required.size());
        metrics.put("obsolete_labels", obsolete.size());
        metrics.put("obsolete_exposure", obsolete.isEmpty() ? null : ratio(obsoleteExposed, obsolete.size()));
        metrics.put("obsolete_span_coverage", obsolete.isEmpty() ? null : ratio(obsoleteCoveredCharacters, obsoleteCharacters));
        metrics.put("serialized_chars", serialized);
        metrics.put("budget_ok", serialized <= pack.budgetChars() && serialized == pack.characters(json));
        metrics.put("source_authenticity", pack.fragments().isEmpty() ? 1.0 : ratio(authentic.size(), pack.fragments().size()));
        metrics.put("all_sources_authentic", authentic.size() == pack.fragments().size());
        metrics.put("foreign_source_count", pack.fragments().stream().filter(f -> foreignIds.contains(f.sourceId())).count());
        metrics.put("injection_roles_preserved", pack.fragments().stream()
                .filter(f -> scenario.injectedSourceIds().contains(f.sourceId()))
                .allMatch(f -> history.containsKey(f.sourceId()) && history.get(f.sourceId()).role().equals(f.role())));
        return metrics;
    }

    private static List<Span> labels(Scenario scenario, List<Gold> labels) {
        Map<String, MemoryMessage> history = historyById(scenario);
        List<Span> spans = new ArrayList<>();
        Set<Span> unique = new HashSet<>();
        for (Gold gold : labels) {
            MemoryMessage source = history.get(gold.sourceId());
            if (source == null || gold.quote() == null || gold.quote().isBlank()
                    || gold.kind() == null || gold.kind().isBlank()) {
                throw new IllegalArgumentException("Invalid gold source/quote: " + scenario.id());
            }
            int start = source.content().indexOf(gold.quote());
            long occurrences = scenario.history().stream().mapToLong(m -> occurrences(m.content(), gold.quote())).sum();
            if (start < 0 || occurrences != 1) {
                throw new IllegalArgumentException("Gold quote must occur exactly once in original history: " + scenario.id());
            }
            if (scenario.query().contains(gold.quote())) {
                throw new IllegalArgumentException("Query contains gold quote: " + scenario.id());
            }
            Span span = new Span(gold.sourceId(), start, start + gold.quote().length());
            if (!unique.add(span)) throw new IllegalArgumentException("Duplicate gold label: " + scenario.id());
            spans.add(span);
        }
        return spans;
    }

    private static long occurrences(String text, String quote) {
        long count = 0;
        int from = 0;
        while ((from = text.indexOf(quote, from)) >= 0) {
            count++;
            from++;
        }
        return count;
    }

    private static Map<String, MemoryMessage> historyById(Scenario scenario) {
        Map<String, MemoryMessage> result = new LinkedHashMap<>();
        for (MemoryMessage message : scenario.history()) {
            if (message.id() == null || message.id().isBlank() || result.putIfAbsent(message.id(), message) != null) {
                throw new IllegalArgumentException("Duplicate or blank history source ID: " + scenario.id());
            }
        }
        return result;
    }

    private static boolean authentic(MemoryFragment fragment, Map<String, MemoryMessage> history) {
        MemoryMessage source = history.get(fragment.sourceId());
        return source != null && source.role().equals(fragment.role()) && source.ordinal() == fragment.ordinal()
                && Objects.equals(source.turnId(), fragment.turnId())
                && fragment.start() >= 0 && fragment.end() >= fragment.start()
                && fragment.end() <= source.content().length()
                && source.content().substring(fragment.start(), fragment.end()).equals(fragment.text());
    }

    private static void assertNotesFromPrefix(List<MemoryMessage> prefix, List<MemoryFragment> notes) {
        Map<String, MemoryMessage> source = new LinkedHashMap<>();
        prefix.forEach(message -> source.put(message.id(), message));
        if (notes.stream().anyMatch(note -> !authentic(note, source))) {
            throw new IllegalStateException("Checkpoint note is not an authentic prefix span");
        }
    }

    private static List<MemoryFragment> mergeNotes(List<MemoryFragment> checkpoint, List<MemoryFragment> recent) {
        Map<String, MemoryFragment> unique = new LinkedHashMap<>();
        List<MemoryFragment> candidates = new ArrayList<>(checkpoint);
        candidates.addAll(recent);
        candidates.stream().sorted(Comparator.comparingInt(MemoryFragment::ordinal).reversed())
                .forEach(note -> unique.putIfAbsent(note.sourceId() + "|" + note.start() + "|" + note.end(), note));
        return unique.values().stream().limit(32)
                .sorted(Comparator.comparingInt(MemoryFragment::ordinal).thenComparingInt(MemoryFragment::start)).toList();
    }

    private static long covered(Span gold, List<MemoryFragment> fragments) {
        List<int[]> intersections = fragments.stream().filter(f -> f.sourceId().equals(gold.sourceId()))
                .map(f -> new int[]{Math.max(f.start(), gold.start()), Math.min(f.end(), gold.end())})
                .filter(span -> span[0] < span[1]).sorted(Comparator.comparingInt(span -> span[0])).toList();
        long total = 0;
        int end = gold.start();
        for (int[] interval : intersections) {
            int start = Math.max(end, interval[0]);
            if (interval[1] > start) total += interval[1] - start;
            end = Math.max(end, interval[1]);
        }
        return total;
    }

    private static List<Map<String, Object>> summaries(List<Map<String, Object>> rows, boolean byTag) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            List<String> tags = byTag ? (List<String>) row.get("tags") : List.of("all");
            for (String tag : tags) {
                String key = row.get("split") + "|" + row.get("strategy") + "|" + row.get("budget_chars") + "|" + tag;
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
            }
        }
        List<Map<String, Object>> summary = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            List<Map<String, Object>> group = entry.getValue();
            Map<String, Object> first = group.get(0);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("split", first.get("split"));
            item.put("strategy", first.get("strategy"));
            item.put("budget_chars", first.get("budget_chars"));
            if (byTag) item.put("tag", entry.getKey().substring(entry.getKey().lastIndexOf('|') + 1));
            item.put("scenarios", group.size());
            for (String metric : List.of("required_source_coverage", "required_span_coverage",
                    "required_quote_coverage", "source_authenticity", "serialized_chars")) {
                item.put(metric, group.stream().mapToDouble(row -> ((Number) row.get(metric)).doubleValue()).average().orElse(0));
            }
            for (String metric : List.of("all_required", "budget_ok", "all_sources_authentic", "injection_roles_preserved")) {
                item.put(metric + "_rate", ratio(group.stream().filter(row -> Boolean.TRUE.equals(row.get(metric))).count(), group.size()));
            }
            var corrections = group.stream().filter(row -> ((Number) row.get("obsolete_labels")).intValue() > 0).toList();
            item.put("obsolete_scenarios", corrections.size());
            item.put("obsolete_exposure", corrections.isEmpty() ? null : corrections.stream()
                    .mapToDouble(row -> ((Number) row.get("obsolete_exposure")).doubleValue()).average().orElse(0));
            item.put("foreign_source_count", group.stream().mapToLong(row -> ((Number) row.get("foreign_source_count")).longValue()).sum());
            List<Double> timings = new ArrayList<>();
            for (Map<String, Object> row : group) {
                @SuppressWarnings("unchecked")
                List<Double> samples = (List<Double>) row.get("pack_samples_ms");
                timings.addAll(samples);
            }
            item.put("pack_p50_ms", percentile(timings, .50));
            item.put("pack_p95_ms", percentile(timings, .95));
            summary.add(item);
        }
        return summary;
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static double elapsedMs(long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static double percentile(List<Double> values, double quantile) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.isEmpty() ? 0 : sorted.get(Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1));
    }

    private static Map<String, String> parseOptions(String[] arguments) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < arguments.length; i += 2) {
            if (i + 1 >= arguments.length || !List.of("--output-dir", "--dataset").contains(arguments[i])
                    || result.putIfAbsent(arguments[i].substring(2), arguments[i + 1]) != null) {
                throw new IllegalArgumentException("Usage: MemoryExperimentRunner [--dataset file] [--output-dir directory]");
            }
        }
        return result;
    }

    private static String csv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "";
        List<String> columns = rows.get(0).keySet().stream()
                .filter(key -> !List.of("fragments", "model_data", "pack_samples_ms").contains(key)).toList();
        StringBuilder csv = new StringBuilder(String.join(",", columns)).append("\n");
        for (Map<String, Object> row : rows) {
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) csv.append(',');
                Object value = row.get(columns.get(i));
                csv.append('"').append(value == null ? "" : value.toString().replace("\"", "\"\"")).append('"');
            }
            csv.append("\n");
        }
        return csv.toString();
    }

    private static String readme(Dataset dataset) {
        return """
                # Memory source-retention diagnostic

                This local experiment does not call a language model or start Spring. It measures which independently
                labeled original-history spans enter a strategy's serialized memory data. It does not measure answer
                correctness, preference compliance, task completion, or whether a model obeyed obsolete instructions.

                Each strategy starts independently from the same chronological scoped history and current query.
                Budgets include the entire compact JSON returned by MemoryPack.modelData(), including source metadata,
                roles, escaping and separators. The unit is Java UTF-16 code units, not provider tokens or UTF-8 bytes.
                Common system prompts, the current query and active-run tool messages are outside this memory budget.
                These components must receive identical separate budgets during a later real-model comparison.

                Checkpoint notes are deterministic original excerpts extracted only from the history prefix.
                The checkpoint ends at snapshot_through_id; the final %d complete rounds form the recent suffix.
                New excerpts are separately extracted from that completed suffix. They are merged with checkpoint
                notes, deduplicated by source/range, and capped at the newest 32 before chronological ordering.
                No gold labels or current query enter extraction. extractive_notes means these excerpts plus recent
                history, not an LLM-generated abstractive summary. Prefix/suffix extraction and merging are timed separately;
                pack_p50_ms and pack_p95_ms use seven independent timed packs after two warmups per combination.
                Timing is machine-specific and excludes persistence, network, tokenization and model inference.

                report.json contains every fragment, serialized model data, checkpoint excerpts and per-scenario metrics.
                results.csv contains per-scenario metrics; summary.csv groups the fixed dev/test partitions separately.
                required_source_coverage counts required sources present even if their labeled quote was omitted.
                required_span_coverage merges authentic fragment intersections with gold spans without double-counting.
                required_quote_coverage requires a whole quote; all_required requires every gold quote in a scenario.
                obsolete_exposure is the fraction of obsolete labeled quotes with ANY authentic overlap, so partial
                exposure also counts. It is null when no obsolete labels exist. Exposure is not model adoption.
                source_authenticity requires source ID, original role, ordinal, turn ID, bounds and exact substring.
                budget_ok independently compares compact serialization with the strategy's character counter.
                foreign_source_count concerns explicit foreign fixtures that are never supplied to packing. It checks
                this diagnostic's scoped inputs, not repository session authorization or production data isolation.
                injection_roles_preserved checks original provenance only. It is not a prompt-injection resistance score.

                The handcrafted %d scenarios have 16 complete rounds each and a fixed dev/test split.
                Eighteen scenarios use a shared turn ID for each user/assistant pair; two retain legacy null turn IDs.
                Test results are diagnostic held-out fixtures, not representative enterprise evidence.
                After changing heuristics, report dev/test separately and avoid tuning against test-case outcomes.
                A later real-model evaluation should freeze model/version/settings, randomly order paired strategy
                trials, preserve the same total token and tool budgets, and use human-authored requirements or objective
                structured output checks. Review substantive answer correctness independently; model self-ratings are
                not an accuracy label. Latest-wins behavior needs answer-level checks beyond this exposure measure.
                Session authorization and assistant/tool-call/result pairing belong in separate service/protocol tests.

                Design references:
                - https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents
                - https://docs.langchain.com/oss/python/langgraph/add-memory
                These sources motivate compaction, notes and valid message history; they do not validate this project.
                """.formatted(dataset.checkpointRecentRounds(), dataset.scenarios().size());
    }
}

