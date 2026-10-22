package com.bedouyu.audiorag.core.loop;
import com.bedouyu.audiorag.rag.QdrantService.SearchHit;
import java.util.*;

/** 在一次根任务中统一分配引用编号，子 Agent 的证据可由主控核查。 */
public class EvidenceLedger {
    private final java.time.Instant createdAt = java.time.Instant.now();
    private final Map<String, Map<String, Object>> evidence = new LinkedHashMap<>();
    private final Map<String, String> keys = new HashMap<>();
    public synchronized List<Map<String, Object>> register(List<SearchHit> hits) {
        return register(hits, 1000);
    }
    public synchronized List<Map<String, Object>> register(List<SearchHit> hits, int perHitCharacterLimit) {
        if (perHitCharacterLimit < 1 || perHitCharacterLimit > 4800) throw new IllegalArgumentException("证据字符预算超限");
        List<Map<String, Object>> result = new ArrayList<>();
        for (var hit : hits) {
            var location = hit.payload() != null && hit.payload().isObject() ? hit.payload().properties().stream()
                    .filter(field -> Set.of("document_id", "text_version", "start_offset", "end_offset", "index_version",
                            "published_at", "fetched_at", "source_host", "evidence_kind")
                            .contains(field.getKey()))
                    .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, field -> field.getValue())) : Map.of();
            String key = hit.source() + ":" + hit.id();
            String id = keys.get(key);
            if (id == null) {
                if (evidence.size() >= 40) break;
                id = "E" + (evidence.size() + 1); keys.put(key, id);
                evidence.put(id, Map.of("id", id, "source_key", key, "title", hit.title(), "source", hit.source(), "score", hit.score(),
                        "location", location));
            }
            result.add(Map.of("citation_id", id, "title", hit.title(), "source", hit.source(),
                    "score", hit.score(), "text", clip(hit.content(), perHitCharacterLimit),
                    "truncated", hit.content().length() > perHitCharacterLimit,
                    "location", location));
        }
        return List.copyOf(result);
    }
    public synchronized List<Map<String, Object>> citations() { return List.copyOf(evidence.values()); }
    /** Old artifacts cannot satisfy a new request for current affairs merely by retaining their dates. */
    public synchronized boolean hasFreshNewsEvidence(java.time.LocalDate from, java.time.LocalDate through) {
        for (var citation : evidence.values()) {
            Object metadata = citation.get("location");
            if (!(metadata instanceof Map<?, ?> location)) continue;
            if (!"live-news-search-summary".equals(value(location.get("evidence_kind")))) continue;
            try {
                var fetched = java.time.Instant.parse(value(location.get("fetched_at")));
                var published = java.time.Instant.parse(value(location.get("published_at")));
                var date = published.atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate();
                if (!fetched.isBefore(createdAt.minusSeconds(1)) && !fetched.isAfter(java.time.Instant.now().plusSeconds(60))
                        && !published.isAfter(fetched.plusSeconds(60)) && !date.isBefore(from) && !date.isAfter(through)) return true;
            } catch (java.time.DateTimeException ignored) { }
        }
        return false;
    }
    private static String value(Object value) {
        return value instanceof tools.jackson.databind.JsonNode node ? node.asText("") : value == null ? "" : String.valueOf(value);
    }
    public synchronized String importArtifact(String content, List<Map<String, Object>> citations) {
        Map<String, String> remap = new HashMap<>();
        for (var citation : citations) {
            String key = String.valueOf(citation.get("source_key"));
            String id = keys.get(key);
            if (id == null) {
                if (evidence.size() >= 40) throw new AgentBudget.AgentStopped("LIMIT_REACHED", "证据引用超过单任务预算");
                id = "E" + (evidence.size() + 1); keys.put(key, id);
                Map<String, Object> copied = new LinkedHashMap<>(citation); copied.put("id", id);
                evidence.put(id, Map.copyOf(copied));
            }
            remap.put(String.valueOf(citation.get("id")), id);
        }
        var matcher = java.util.regex.Pattern.compile("\\[(E[0-9]+)\\]").matcher(content);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result,
                "[" + remap.getOrDefault(matcher.group(1), matcher.group(1)) + "]");
        matcher.appendTail(result); return result.toString();
    }
    public synchronized List<String> unknownCitations(String text) {
        List<String> unknown = new ArrayList<>();
        var matcher = java.util.regex.Pattern.compile("\\[(E[0-9]+)\\]").matcher(text);
        while (matcher.find()) if (!evidence.containsKey(matcher.group(1))) unknown.add(matcher.group(1));
        return unknown.stream().distinct().toList();
    }
    public static String clip(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}
