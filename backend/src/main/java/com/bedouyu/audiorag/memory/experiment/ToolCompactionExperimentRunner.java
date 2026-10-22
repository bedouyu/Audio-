package com.bedouyu.audiorag.memory.experiment;

import com.bedouyu.audiorag.core.loop.AgentBudget;
import com.bedouyu.audiorag.core.loop.AgentTranscript;
import com.bedouyu.audiorag.model.dto.AgentModelReply;
import com.bedouyu.audiorag.model.dto.FunctionCall;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Fixed synthetic replay of the production transcript selector; no model, tool, DB or network calls. */
public final class ToolCompactionExperimentRunner {
    public static final int CONTEXT_LIMIT = 48000;
    public static final List<String> STRATEGIES = List.of("preview", "structured");
    private static final List<String> REFERENCE_KINDS = List.of("artifact", "citation", "source");

    public record Fixture(String id, List<Map<String, Object>> initial,
                          Map<String, Object> oldAssistant, List<Map<String, Object>> oldObservations,
                          Map<String, Object> latestAssistant, List<Map<String, Object>> latestObservations) {
        public Fixture {
            initial = List.copyOf(initial);
            oldAssistant = Map.copyOf(oldAssistant);
            oldObservations = List.copyOf(oldObservations);
            latestAssistant = Map.copyOf(latestAssistant);
            latestObservations = List.copyOf(latestObservations);
        }

        public List<Map<String, Object>> originalMessages() {
            List<Map<String, Object>> result = new ArrayList<>(initial);
            result.add(oldAssistant);
            result.addAll(oldObservations);
            result.add(latestAssistant);
            result.addAll(latestObservations);
            return List.copyOf(result);
        }

        public List<Map<String, Object>> latestRound() {
            List<Map<String, Object>> result = new ArrayList<>();
            result.add(latestAssistant);
            result.addAll(latestObservations);
            return List.copyOf(result);
        }
    }

    private record Reference(String kind, String value) { }

    private ToolCompactionExperimentRunner() { }

    public static void main(String[] arguments) throws Exception {
        Path output = null;
        for (int index = 0; index < arguments.length; index++) {
            if (!arguments[index].equals("--output-dir") || ++index >= arguments.length || output != null)
                throw new IllegalArgumentException("Usage: ToolCompactionExperimentRunner [--output-dir PATH]");
            output = Path.of(arguments[index]);
        }
        if (output == null) output = Path.of(".cache/memory-experiments/tool-compaction-" + System.currentTimeMillis());
        output = output.toAbsolutePath().normalize();
        ObjectMapper json = new ObjectMapper();
        Map<String, Object> report = run(json);
        Files.createDirectories(output);
        Files.writeString(output.resolve("tool-compaction-report.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("tool-compaction-results.csv"), csv(rows(report, "rows")), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("tool-compaction-summary.csv"), csv(rows(report, "summary")), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("tool-compaction-README.md"), readme(), StandardCharsets.UTF_8);
        System.out.println("Offline tool-compaction replay: 8 fixtures, 16 strategy runs. Report: " + output);
    }

    /** Eight known old observations; every frame is synthetic and every reference follows its large body. */
    public static List<Fixture> fixtures(ObjectMapper json) {
        List<Map<String, Object>> metadata = List.of(
                object("status", "OK", "artifact_id", "artifact-fixture-001", "artifact_status", "NEEDS_REVIEW", "kind", "DRAFT"),
                object("status", "OK", "citation_id", "citation-fixture-002", "source_id", "source-fixture-002",
                        "location", object("document_id", "document-fixture-002", "start_offset", 24, "end_offset", 80)),
                object("status", "PENDING_REVIEW", "artifact_id", "artifact-fixture-003", "source_id", "source-fixture-003",
                        "artifact_ids", List.of("artifact-fixture-003-related"),
                        "input_artifact_ids", List.of("artifact-fixture-003-input")),
                object("status", "DEGRADED", "evidence", List.of(
                        object("citation_id", "citation-fixture-004a", "source_id", "source-fixture-004a", "title", "Synthetic A"),
                        object("citation_id", "citation-fixture-004b", "source_id", "source-fixture-004b", "title", "Synthetic B"))),
                object("status", "OK", "citations", List.of(
                        object("id", "citation-fixture-005a", "source_id", "source-fixture-005a"),
                        object("id", "citation-fixture-005b", "source_id", "source-fixture-005b"))),
                object("status", "QUEUED", "artifacts", List.of(
                        object("id", "artifact-fixture-006a", "artifact_id", "artifact-fixture-006a", "source_id", "source-fixture-006a"),
                        object("id", "artifact-fixture-006b", "artifact_id", "artifact-fixture-006b", "source_id", "source-fixture-006b"))),
                object("status", "OK", "source_id", "source-fixture-007-root", "fragments", List.of(
                        object("source_id", "source-fixture-007a", "turn_id", "turn-fixture-007a", "start", 5, "end", 18),
                        object("source_id", "source-fixture-007b", "turn_id", "turn-fixture-007b", "start", 21, "end", 34))),
                object("status", "PARTIAL", "artifact_ids", List.of("artifact-fixture-008a", "artifact-fixture-008b"),
                        "citation_id", "citation-fixture-008", "source_id", "source-fixture-008"));
        List<Fixture> result = new ArrayList<>();
        for (int index = 0; index < metadata.size(); index++) {
            String id = "tool-fixture-" + String.format(java.util.Locale.ROOT, "%02d", index + 1);
            Map<String, Object> original = new LinkedHashMap<>();
            original.put("content", ("SYNTHETIC BODY " + id + " ; ").repeat(1800));
            original.putAll(metadata.get(index)); // Preserve this insertion order: IDs are outside the prefix preview.
            String oldId = id + "-old-call";
            String latestId = id + "-latest-call";
            Map<String, Object> latest = object("content", index == 7 ? "LATEST BODY MUST REMAIN INTACT ; ".repeat(1800)
                    : "latest complete synthetic body ; ".repeat(30), "status", "OK",
                    "artifact_id", "latest-artifact-" + (index + 1), "source_id", "latest-source-" + (index + 1));
            result.add(new Fixture(id, List.of(object("role", "system", "content", "Synthetic offline formatting replay.")),
                    assistant(oldId, id), List.of(tool(oldId, json.writeValueAsString(original))),
                    assistant(latestId, id), List.of(tool(latestId, json.writeValueAsString(latest)))));
        }
        return List.copyOf(result);
    }

    public static Map<String, Object> run(ObjectMapper json) {
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (Fixture fixture : fixtures(json)) {
            List<Map<String, Object>> source = fixture.originalMessages();
            if (!protocolPaired(source)) throw new IllegalStateException("Fixture protocol is incomplete");
            manifest.add(object("fixture_id", fixture.id(), "original_observation_sha256",
                    fixture.oldObservations().stream().map(observation -> sha256(String.valueOf(observation.get("content")))).toList(),
                    "original_references", referenceData(originalReferences(fixture, json)),
                    "latest_round_sha256", sha256(json.writeValueAsString(fixture.latestRound()))));
            for (String strategy : STRATEGIES) {
                AgentTranscript transcript = new AgentTranscript(fixture.initial(), json, strategy);
                transcript.addRound(fixture.oldAssistant(), fixture.oldObservations());
                transcript.addRound(fixture.latestAssistant(), fixture.latestObservations());
                Map<String, Object> row;
                try {
                    row = metrics(fixture, transcript.messages(), json);
                    row.put("outcome", "PACKED");
                    row.put("explicit_limit", false);
                } catch (AgentBudget.AgentStopped stopped) {
                    if (!stopped.status().equals("LIMIT_REACHED")) throw stopped;
                    row = metrics(fixture, null, json);
                    row.put("outcome", stopped.status());
                    row.put("explicit_limit", true);
                    row.put("limit_message", stopped.getMessage());
                }
                row.put("fixture_id", fixture.id());
                row.put("strategy", strategy);
                results.add(row);
            }
        }
        return object("schema_version", 1, "experiment_id", "tool-compaction-offline-v1",
                "created_at", Instant.now().toString(), "context_budget_chars", CONTEXT_LIMIT,
                "fixture_count", 8, "strategy_run_count", results.size(), "strategies", STRATEGIES,
                "claim_boundary", "Synthetic JSON/protocol preservation only; no actual tool execution or LLM answer correctness.",
                "reference_counting", "Distinct (kind,value) identities in parsed structural reference fields; preview strings never count.",
                "fixtures", manifest, "rows", results, "summary", summarize(results));
    }

    /** Null output means no observable packed context; all output-derived metrics remain null. */
    public static Map<String, Object> metrics(Fixture fixture, List<Map<String, Object>> output, ObjectMapper json) {
        List<Map<String, Object>> input = fixture.originalMessages();
        Set<Reference> originalRefs = originalReferences(fixture, json);
        Map<String, Object> row = object("input_context_chars", json.writeValueAsString(input).length(),
                "protocol_chars_without_observation_content", protocolCharacters(input, json),
                "original_old_observation_chars", fixture.oldObservations().stream()
                        .mapToInt(observation -> String.valueOf(observation.get("content")).length()).sum(),
                "old_observation_count", fixture.oldObservations().size(), "original_references", referenceData(originalRefs),
                "latest_round_sha256_before", sha256(json.writeValueAsString(fixture.latestRound())));
        for (String kind : REFERENCE_KINDS)
            row.put(kind + "_reference_total", originalRefs.stream().filter(reference -> reference.kind().equals(kind)).count());
        List<String> outputFields = List.of("output_context_chars", "context_within_budget", "old_json_valid_count", "old_json_valid_ratio",
                "old_preview_prefix_json_valid_count", "old_preview_prefix_count", "status_exact_count", "status_exact_ratio",
                "old_observation_after_chars", "old_body_retained_chars", "old_body_reduction_chars", "old_body_reduction_ratio",
                "protocol_call_id_pairing_preserved", "protocol_frames_preserved", "reasoning_preserved", "latest_round_preserved",
                "latest_round_sha256_after", "retained_references", "invented_references", "invented_reference_count", "old_observation_outputs");
        outputFields.forEach(field -> row.put(field, null));
        for (String kind : REFERENCE_KINDS) {
            row.put(kind + "_reference_retained", null);
            row.put(kind + "_reference_exact_ratio", null);
        }
        int originalBodyChars = fixture.oldObservations().stream()
                .mapToInt(observation -> parseObject(String.valueOf(observation.get("content")), json).path("content").asText().length()).sum();
        row.put("old_body_original_chars", originalBodyChars);
        if (output == null) return row;

        int contextChars = json.writeValueAsString(output).length();
        row.put("output_context_chars", contextChars);
        row.put("context_within_budget", contextChars <= CONTEXT_LIMIT);
        row.put("protocol_call_id_pairing_preserved", protocolPaired(output)
                && callIds(input).equals(callIds(output)) && observationIds(input).equals(observationIds(output)));
        row.put("protocol_frames_preserved", framesWithoutToolContent(input).equals(framesWithoutToolContent(output)));
        row.put("reasoning_preserved", assistantReasoning(input).equals(assistantReasoning(output)));
        int latestStart = fixture.initial().size() + 1 + fixture.oldObservations().size();
        List<Map<String, Object>> latest = output.size() >= latestStart ? output.subList(latestStart, output.size()) : List.of();
        row.put("latest_round_preserved", fixture.latestRound().equals(latest));
        row.put("latest_round_sha256_after", sha256(json.writeValueAsString(latest)));
        Set<Reference> observedRefs = new LinkedHashSet<>();
        List<Map<String, Object>> oldOutputs = new ArrayList<>();
        int valid = 0, statuses = 0, prefixCount = 0, validPrefixes = 0, bodyRetained = 0, afterChars = 0;
        for (int index = 0; index < fixture.oldObservations().size(); index++) {
            JsonNode original = parseObject(String.valueOf(fixture.oldObservations().get(index).get("content")), json);
            int outputIndex = fixture.initial().size() + 1 + index;
            if (outputIndex >= output.size()) continue;
            Map<String, Object> observation = output.get(outputIndex);
            if (!Objects.equals(observation.get("role"), "tool")) continue;
            String text = String.valueOf(observation.get("content"));
            afterChars += text.length();
            oldOutputs.add(object("tool_call_id", observation.get("tool_call_id"), "content", text));
            JsonNode parsed = tryParseObject(text, json);
            if (parsed == null) continue;
            valid++;
            if (original.path("status").isTextual() && original.path("status").asText().equals(parsed.path("status").asText())) statuses++;
            collectReferences(parsed, "", observedRefs);
            String originalBody = original.path("content").asText();
            int retainedBody = parsed.path("content").isTextual()
                    ? retainedPlainBodyPrefix(originalBody, parsed.path("content").asText()) : 0;
            if (parsed.path("preview").isTextual()) {
                prefixCount++;
                String prefix = parsed.path("preview").asText();
                if (tryParseObject(prefix, json) != null) validPrefixes++;
                retainedBody = Math.max(retainedBody, retainedPlainBodyPrefix(originalBody, prefix));
            }
            bodyRetained += retainedBody;
        }
        Set<Reference> retained = new LinkedHashSet<>(observedRefs);
        retained.retainAll(originalRefs);
        Set<Reference> invented = new LinkedHashSet<>(observedRefs);
        invented.removeAll(originalRefs);
        row.put("retained_references", referenceData(retained));
        row.put("invented_references", referenceData(invented));
        row.put("invented_reference_count", invented.size());
        row.put("old_observation_outputs", oldOutputs);
        row.put("old_json_valid_count", valid);
        row.put("old_json_valid_ratio", ratio(valid, fixture.oldObservations().size()));
        row.put("old_preview_prefix_count", prefixCount);
        row.put("old_preview_prefix_json_valid_count", validPrefixes);
        row.put("status_exact_count", statuses);
        row.put("status_exact_ratio", ratio(statuses, fixture.oldObservations().size()));
        row.put("old_observation_after_chars", afterChars);
        row.put("old_body_retained_chars", bodyRetained);
        row.put("old_body_reduction_chars", originalBodyChars - bodyRetained);
        row.put("old_body_reduction_ratio", ratio(originalBodyChars - bodyRetained, originalBodyChars));
        for (String kind : REFERENCE_KINDS) {
            long total = originalRefs.stream().filter(reference -> reference.kind().equals(kind)).count();
            long count = retained.stream().filter(reference -> reference.kind().equals(kind)).count();
            row.put(kind + "_reference_retained", count);
            row.put(kind + "_reference_exact_ratio", ratio(count, total));
        }
        return row;
    }

    public static boolean protocolPaired(List<Map<String, Object>> frames) {
        Set<String> allCalls = new LinkedHashSet<>();
        Set<String> pending = new LinkedHashSet<>();
        for (Map<String, Object> frame : frames) {
            if (Objects.equals(frame.get("role"), "assistant")) {
                if (!pending.isEmpty()) return false;
                if (!(frame.get("tool_calls") instanceof List<?> calls)) continue;
                for (Object value : calls) {
                    if (!(value instanceof Map<?, ?> call) || !(call.get("id") instanceof String id)
                            || id.isBlank() || !allCalls.add(id)) return false;
                    pending.add(id);
                }
            } else if (Objects.equals(frame.get("role"), "tool")) {
                if (!(frame.get("tool_call_id") instanceof String id) || !pending.remove(id)) return false;
            } else if (!pending.isEmpty()) return false;
        }
        return pending.isEmpty();
    }

    private static Set<Reference> originalReferences(Fixture fixture, ObjectMapper json) {
        Set<Reference> result = new LinkedHashSet<>();
        fixture.oldObservations().forEach(observation ->
                collectReferences(parseObject(String.valueOf(observation.get("content")), json), "", result));
        return result;
    }

    private static void collectReferences(JsonNode node, String parent, Set<Reference> result) {
        if (node.isObject()) {
            for (var field : node.properties()) {
                String key = field.getKey();
                JsonNode value = field.getValue();
                if (Set.of("preview", "content", "text", "body", "message", "reasoning_content").contains(key)) continue;
                String kind = switch (key) {
                    case "artifact_id", "artifact_ids", "input_artifact_ids" -> "artifact";
                    case "citation_id" -> "citation";
                    case "source_id" -> "source";
                    case "id" -> parent.equals("artifacts") ? "artifact" : parent.equals("citations") ? "citation" : null;
                    default -> null;
                };
                if (kind != null && value.isTextual()) result.add(new Reference(kind, value.asText()));
                if (kind != null && value.isArray()) for (JsonNode item : value)
                    if (item.isTextual()) result.add(new Reference(kind, item.asText()));
                if (value.isObject() || value.isArray()) collectReferences(value, key, result);
            }
        } else if (node.isArray()) for (JsonNode item : node) collectReferences(item, parent, result);
        // Body/preview containers are excluded even if malformed data makes them objects. Text
        // leaves are never reparsed or searched for reference IDs.
    }

    private static List<Map<String, Object>> referenceData(Set<Reference> references) {
        return references.stream().sorted(java.util.Comparator.comparing(Reference::kind).thenComparing(Reference::value))
                .map(reference -> object("kind", reference.kind(), "value", reference.value())).toList();
    }

    private static JsonNode tryParseObject(String content, ObjectMapper json) {
        try {
            JsonNode result = json.readTree(content);
            return result != null && result.isObject() ? result : null;
        } catch (RuntimeException invalid) { return null; }
    }

    private static JsonNode parseObject(String content, ObjectMapper json) {
        JsonNode result = tryParseObject(content, json);
        if (result == null) throw new IllegalArgumentException("Fixture must contain a complete original JSON object");
        return result;
    }

    private static int retainedPlainBodyPrefix(String original, String preview) {
        if (original.isEmpty()) return 0;
        int start = preview.indexOf(original.substring(0, Math.min(32, original.length())));
        if (start < 0) return 0;
        int matched = 0;
        while (matched < original.length() && start + matched < preview.length()
                && original.charAt(matched) == preview.charAt(start + matched)) matched++;
        return matched;
    }

    private static int protocolCharacters(List<Map<String, Object>> frames, ObjectMapper json) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> frame : frames) {
            Map<String, Object> copy = new LinkedHashMap<>(frame);
            if (Objects.equals(frame.get("role"), "tool")) copy.put("content", "");
            result.add(copy);
        }
        return json.writeValueAsString(result).length();
    }

    private static List<Map<String, Object>> framesWithoutToolContent(List<Map<String, Object>> frames) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> frame : frames) {
            Map<String, Object> copy = new LinkedHashMap<>(frame);
            if (Objects.equals(frame.get("role"), "tool")) copy.remove("content");
            result.add(copy);
        }
        return result;
    }

    private static List<Object> assistantReasoning(List<Map<String, Object>> frames) {
        return frames.stream().filter(frame -> Objects.equals(frame.get("role"), "assistant"))
                .map(frame -> frame.get("reasoning_content")).toList();
    }

    private static List<String> callIds(List<Map<String, Object>> frames) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> frame : frames) if (frame.get("tool_calls") instanceof List<?> calls)
            for (Object value : calls) if (value instanceof Map<?, ?> call) result.add(String.valueOf(call.get("id")));
        return result;
    }

    private static List<String> observationIds(List<Map<String, Object>> frames) {
        return frames.stream().filter(frame -> Objects.equals(frame.get("role"), "tool"))
                .map(frame -> String.valueOf(frame.get("tool_call_id"))).toList();
    }

    private static List<Map<String, Object>> summarize(List<Map<String, Object>> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String strategy : STRATEGIES) {
            List<Map<String, Object>> selected = rows.stream().filter(row -> row.get("strategy").equals(strategy)).toList();
            List<Map<String, Object>> packed = selected.stream().filter(row -> row.get("outcome").equals("PACKED")).toList();
            Map<String, Object> summary = object("strategy", strategy, "fixture_runs", selected.size(), "packed_runs", packed.size(),
                    "explicit_limit_runs", selected.size() - packed.size(), "observable_reference_denominator_runs", packed.size());
            for (String kind : REFERENCE_KINDS) {
                long total = sum(packed, kind + "_reference_total");
                long retained = sum(packed, kind + "_reference_retained");
                summary.put(kind + "_reference_total", total);
                summary.put(kind + "_reference_retained", retained);
                summary.put(kind + "_reference_exact_ratio", ratio(retained, total));
            }
            summary.put("status_exact_ratio", ratio(sum(packed, "status_exact_count"), sum(packed, "old_observation_count")));
            summary.put("old_json_valid_ratio", ratio(sum(packed, "old_json_valid_count"), sum(packed, "old_observation_count")));
            summary.put("old_body_reduction_ratio", ratio(sum(packed, "old_body_reduction_chars"), sum(packed, "old_body_original_chars")));
            summary.put("protocol_preserved_runs", packed.stream().filter(row -> Boolean.TRUE.equals(row.get("protocol_frames_preserved"))
                    && Boolean.TRUE.equals(row.get("protocol_call_id_pairing_preserved")) && Boolean.TRUE.equals(row.get("reasoning_preserved"))).count());
            summary.put("latest_round_preserved_runs", packed.stream().filter(row -> Boolean.TRUE.equals(row.get("latest_round_preserved"))).count());
            summary.put("context_within_budget_runs", packed.stream().filter(row -> Boolean.TRUE.equals(row.get("context_within_budget"))).count());
            summary.put("invented_reference_count", sum(packed, "invented_reference_count"));
            result.add(summary);
        }
        return result;
    }

    private static long sum(List<Map<String, Object>> rows, String field) {
        return rows.stream().map(row -> row.get(field)).filter(Number.class::isInstance).mapToLong(value -> ((Number) value).longValue()).sum();
    }

    private static Double ratio(long numerator, long denominator) { return denominator == 0 ? null : (double) numerator / denominator; }

    private static Map<String, Object> assistant(String id, String fixture) {
        return new AgentModelReply("", "SYNTHETIC OPAQUE PROTOCOL REASONING " + id,
                List.of(new FunctionCall(id, "offline_fixture_probe", "{\"fixture\":\"" + fixture + "\"}")), "tool_calls").assistantMessage();
    }

    private static Map<String, Object> tool(String id, String content) { return object("role", "tool", "tool_call_id", id, "content", content); }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> report, String key) { return (List<Map<String, Object>>) report.get(key); }

    private static String csv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return "";
        List<String> headers = rows.stream().flatMap(row -> row.keySet().stream()).distinct()
                .filter(key -> rows.stream().map(row -> row.get(key)).noneMatch(value -> value instanceof Map<?, ?> || value instanceof List<?>)).toList();
        StringBuilder result = new StringBuilder(String.join(",", headers)).append('\n');
        for (Map<String, Object> row : rows) {
            for (int index = 0; index < headers.size(); index++) {
                if (index > 0) result.append(',');
                Object value = row.get(headers.get(index));
                if (value != null) result.append('"').append(String.valueOf(value).replace("\"", "\"\"")).append('"');
            }
            result.append('\n');
        }
        return result.toString();
    }

    private static String readme() {
        return """
                # Offline tool-observation compaction replay

                This replays production AgentTranscript with preview and structured on the same eight fixed synthetic inputs.
                Each input has exactly two completed assistant tool_calls / tool-observation rounds. The old JSON has a
                large, plain-text body followed by its status and known artifact/citation/source_id fields. Protocol
                metadata stays small. Fixture 08 has an oversized latest observation and must stop with LIMIT_REACHED.
                The latest round is never intentionally compacted, and reasoning_content and tool_call_id are compared
                exactly whenever a packed context is returned. There is no actual tool execution in this replay;
                no model, database, or network is called.

                ## Reading the metrics

                - old_json_valid_ratio measures the returned outer JSON object. Preview also has a valid JSON wrapper;
                  old_preview_prefix_json_valid_count separately checks the raw, normally incomplete JSON prefix inside it.
                - status_exact_ratio compares the original root status with the returned root status. A new COMPACTED
                  marker is not the original status. Synthetic OK, QUEUED, etc. do not mean a real tool succeeded.
                - Reference retention is the intersection of distinct (kind, exact value) identities in parsed fields:
                  artifact_id/artifact_ids/input_artifact_ids; citation_id; source_id; and artifacts[].id/citations[].id.
                  Strings in content or preview never count. Unknown structural references are reported as invented.
                  Duplicated declarations of one identity count once. Ratios with no original references are null.
                - Body reduction measures the synthetic root content characters. For preview, retained body is the exact
                  original prefix visible inside its raw preview string. The fixed bodies have no JSON-escaped characters;
                  this character measure is not a general estimator for arbitrary escaped or nested tool payloads.
                - Context size is the actual serialized message-list length in Java UTF-16 characters, with a 48000 limit.
                  Protocol pairing, complete protocol frames, opaque reasoning and complete latest round are independent checks.
                - LIMIT_REACHED returns no observable packed context, so output metrics are null, not assumed successful or zero.
                  Retention summaries use only PACKED runs and explicitly expose their denominator and LIMIT count.

                The JSON stores source hashes, expected and retained reference identities, compacted old observations,
                row metrics and summaries. CSV files expose the scalar row and summary metrics. Files use a tool-compaction
                prefix so this replay can share an output directory with the separate conversation-memory experiment.

                These fixtures test format and structural preservation only. They do not establish real source existence,
                factual correctness, actual tool success, LLM answer accuracy, or any ranking between models.
                """;
    }
}
