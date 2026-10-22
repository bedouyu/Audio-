package com.bedouyu.audiorag.core.loop;

import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** 保留 assistant/tool 配对；超预算先压缩旧观察正文，不丢失工具调用编号。 */
public class AgentTranscript {
    private static final int CONTEXT_LIMIT = 48000, OBSERVATION_LIMIT = 1000;
    private static final List<String> METADATA = List.of("status", "requested_retrieval", "requested_context",
            "actual_retrieval", "actual_context", "requested_strategy", "actual_strategy", "retrieval_strategy",
            "context_strategy", "artifact_id", "artifact_kind", "artifact_status", "kind", "agent_id",
            "citation_id", "title", "source", "hit_count", "candidate_count", "context_tokens_bge",
            "context_tokens", "contexttokens", "context_token_budget", "offset", "next_offset", "total_chars", "has_more",
            "source_id", "turn_id", "start", "end", "strategy", "budget_chars", "scanned_messages", "checkpoint_through_id",
            "role", "run_id", "task_status", "bm25_index_cache", "index_revision", "content_hash",
            "source_content_hash", "rendered_content_hash", "read_full", "local_check_passed");
    private static final List<String> LOCATION = List.of("document_id", "text_version", "start_offset", "end_offset",
            "index_version", "page", "page_number", "line", "start_line", "end_line", "section", "source_id", "turn_id", "start", "end");
    private static final List<String> COLLECTIONS = List.of("warnings", "evidence", "citations", "artifacts",
            "artifact_ids", "input_ids", "input_artifact_ids", "fragments", "checkpoint_warnings");
    private static final Set<String> REFERENCES = Set.of("id", "artifact_id", "citation_id", "document_id", "source",
            "text_version", "index_version", "status", "requested_retrieval", "requested_context",
                        "actual_retrieval", "actual_context", "requested_strategy", "actual_strategy", "retrieval_strategy", "context_strategy", "source_id", "turn_id", "run_id", "index_revision",
                        "content_hash", "source_content_hash", "rendered_content_hash");
    private final List<Map<String, Object>> initial;
    private final List<List<Map<String, Object>>> rounds = new ArrayList<>();
    private final ObjectMapper json;
    private final String compactionStrategy;
    public AgentTranscript(List<Map<String, Object>> initial, ObjectMapper json) {
        this(initial, json, "structured");
    }
    public AgentTranscript(List<Map<String, Object>> initial, ObjectMapper json, String compactionStrategy) {
        if (!Set.of("preview", "structured").contains(compactionStrategy))
            throw new IllegalArgumentException("未知工具观察压缩策略：" + compactionStrategy);
        this.initial = List.copyOf(initial); this.json = json; this.compactionStrategy = compactionStrategy;
    }
    public void addRound(Map<String, Object> assistant, List<Map<String, Object>> observations) {
        List<Map<String, Object>> round = new ArrayList<>(); round.add(assistant); round.addAll(observations);
        rounds.add(round);
    }
    public void feedback(Map<String, Object> assistant, String error) {
        rounds.add(new ArrayList<>(List.of(assistant,
                Map.of("role", "user", "content", "输出检查未通过：" + error + "。请根据已有证据修正输出。"))));
    }
    public List<Map<String, Object>> messages() {
        List<Map<String, Object>> result = flatten();
        if (json.writeValueAsString(result).length() <= CONTEXT_LIMIT) return result;
        for (int i = 0; i + 1 < rounds.size(); i++) {
            List<Map<String, Object>> round = rounds.get(i);
            for (int j = 1; j < round.size(); j++) {
                Map<String, Object> message = round.get(j);
                if ("tool".equals(message.get("role")) && String.valueOf(message.get("content")).length() > OBSERVATION_LIMIT) {
                    var shortened = new LinkedHashMap<>(message);
                    shortened.put("content", compact(String.valueOf(message.get("content"))));
                    round.set(j, shortened);
                }
            }
            result = flatten();
            if (json.writeValueAsString(result).length() <= CONTEXT_LIMIT) return result;
        }
        // reasoning_content 如存在必须完整回灌，不静默截断供应商要求保留的字段。
        throw new AgentBudget.AgentStopped("LIMIT_REACHED", "Agent 上下文超过预算，保留任务产物后停止");
    }
    /** 完整请求预算含工具schema，可能先于字符上限触发。只压缩模型已经看过的旧轮观察。 */
    public boolean compactForBudget() {
        boolean changed = false;
        for (int i = 0; i + 1 < rounds.size(); i++) {
            var round = rounds.get(i);
            for (int j = 1; j < round.size(); j++) {
                var message = round.get(j);
                if (!"tool".equals(message.get("role"))) continue;
                String original = String.valueOf(message.get("content"));
                if (original.length() <= OBSERVATION_LIMIT) continue;
                String shortened = compact(original);
                var copy = new LinkedHashMap<>(message); copy.put("content", shortened);
                round.set(j, copy); changed = true;
            }
        }
        return changed;
    }
    private String compact(String content) {
        if ("structured".equals(compactionStrategy)) {
            try {
                var parsed = json.readTree(content);
                if (parsed.isObject()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> source = json.convertValue(parsed, Map.class);
                    // 所有候选都重新序列化完整对象；超过预算时减少条目，绝不切割 JSON 文本。
                    for (int[] budget : new int[][]{{8, 160}, {4, 120}, {2, 80}, {1, 60}, {1, 32}, {0, 32}, {0, 8}}) {
                        String candidate = json.writeValueAsString(structured(source, content.length(), budget[0], budget[1]));
                        if (candidate.length() <= OBSERVATION_LIMIT) return candidate;
                    }
                }
            } catch (RuntimeException ignored) { /* 非 JSON 观察使用明确标记的原始文本预览。 */ }
        }
        return preview(content);
    }
    private Map<String, Object> structured(Map<String, Object> source, int originalChars, int maxItems, int textLimit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("compaction_status", "COMPACTED"); result.put("compaction_strategy", "structured");
        result.put("content_compacted", true); result.put("original_chars", originalChars);
        result.put("message", "正文已压缩；请按 source/location 或 artifact_id 重新读取原文");
        copyMetadata(source, result, textLimit);
        if (!result.containsKey("status")) result.put("status", "COMPACTED");
        boolean truncated = false;
        int omitted = 0;
        for (String key : COLLECTIONS) {
            if (!(source.get(key) instanceof List<?> items)) continue;
            List<Object> retained = new ArrayList<>();
            // 降级警告至少保留一条；证据条目不足以装入预算时仍可解释实际策略。
            int itemLimit = key.endsWith("warnings") ? Math.max(1, maxItems) : maxItems;
            for (Object item : items.stream().limit(itemLimit).toList()) {
                if (item instanceof Map<?, ?> entry) {
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    copyMetadata(entry, metadata, textLimit);
                    if (entry.containsKey("id")) metadata.put("id", entry.get("id"));
                    metadata.put("content_compacted", true);
                    retained.add(metadata);
                    truncated |= hasLongMetadata(entry, textLimit);
                } else if (item instanceof String || item instanceof Number || item instanceof Boolean) {
                    retained.add(key.endsWith("ids") ? item : small(item, textLimit));
                    truncated |= !key.endsWith("ids") && item instanceof String text && text.length() > textLimit;
                }
            }
            result.put(key, retained);
            int count = items.size() - retained.size();
            if (count > 0) { result.put(key + "_omitted", count); omitted += count; }
        }
        result.put("omitted_entries", omitted);
        if (truncated || hasLongMetadata(source, textLimit)) result.put("metadata_truncated", true);
        return result;
    }
    private void copyMetadata(Map<?, ?> source, Map<String, Object> target, int textLimit) {
        for (String key : METADATA) {
            Object value = source.get(key);
            if (value instanceof String || value instanceof Number || value instanceof Boolean)
                target.put(key, REFERENCES.contains(key) ? value : small(value, textLimit));
        }
        if (source.get("location") instanceof Map<?, ?> location) {
            Map<String, Object> coordinates = new LinkedHashMap<>();
            for (String key : LOCATION) {
                Object value = location.get(key);
                if (value instanceof String || value instanceof Number || value instanceof Boolean)
                    coordinates.put(key, REFERENCES.contains(key) ? value : small(value, textLimit));
            }
            target.put("location", coordinates);
        }
    }
    private boolean hasLongMetadata(Map<?, ?> source, int textLimit) {
        for (String key : METADATA)
            if (!REFERENCES.contains(key) && source.get(key) instanceof String value && value.length() > textLimit) return true;
        if (source.get("location") instanceof Map<?, ?> location)
            for (String key : LOCATION)
                if (!REFERENCES.contains(key) && location.get(key) instanceof String value && value.length() > textLimit) return true;
        return false;
    }
    private Object small(Object value, int textLimit) {
        return value instanceof String text ? EvidenceLedger.clip(text, textLimit) : value;
    }
    private String preview(String content) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "COMPACTED"); result.put("compaction_strategy", "preview");
        result.put("content_compacted", true); result.put("original_chars", content.length());
        result.put("message", "以下仅为原始文本前缀，可能不是完整 JSON；按 source 或 artifact_id 重新读取原文");
        int limit = Math.min(700, content.length());
        while (true) {
            result.put("preview", EvidenceLedger.clip(content, limit));
            result.put("omitted_chars", Math.max(0, content.length() - limit));
            String candidate = json.writeValueAsString(result);
            if (candidate.length() <= OBSERVATION_LIMIT) return candidate;
            limit = Math.max(0, limit - 100);
        }
    }
    private List<Map<String, Object>> flatten() {
        List<Map<String, Object>> result = new ArrayList<>(initial);
        rounds.forEach(result::addAll); return List.copyOf(result);
    }
}
