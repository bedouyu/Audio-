package com.bedouyu.audiorag.memory;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.memory.strategy.*;
import com.bedouyu.audiorag.model.entity.ChatMessage;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.stream.Collectors;

/** 原始历史、可核对的摘记检查点、查询时策略是三层，不能用派生摘记覆盖原始历史。 */
@Service
public class MemoryContextService {
    public static final Set<String> STRATEGIES = Set.of("recent_window", "query_recall", "extractive_notes", "hybrid");
    private final AudioRagRepository repository;
    private final MemoryCheckpointStore checkpoints;
    private final ObjectMapper json;
    private final MemoryStrategyEngine engine;
    private final String defaultStrategy;
    private final int defaultBudget;
    public MemoryContextService(AudioRagRepository repository, MemoryCheckpointStore checkpoints, ObjectMapper json,
            @Value("${audiorag.memory.strategy:hybrid}") String defaultStrategy,
            @Value("${audiorag.memory.context-budget-chars:6000}") int budget) {
        validate(defaultStrategy, budget);
        this.repository = repository; this.checkpoints = checkpoints; this.json = json;
        this.engine = new MemoryStrategyEngine(json); this.defaultStrategy = defaultStrategy; this.defaultBudget = budget;
    }
    public Prepared prepare(long user, long project, String session, String query, String requestedStrategy) {
        return prepare(user, project, session, query, requestedStrategy, defaultBudget, true);
    }
    public Prepared prepare(long user, long project, String session, String query, String requestedStrategy,
                            int budget, boolean saveCheckpoint) {
        String strategy = requestedStrategy == null || requestedStrategy.isBlank() ? defaultStrategy : requestedStrategy;
        validate(strategy, budget);
        // repository方法先核验session/user/project，不让模型传入其他范围。
        var rows = repository.memoryMessages(user, project, session, 400);
        int scanned = rows.size(), boundarySkipped = 0;
        if (scanned == 400 && rows.get(0).getTurnId() == null) {
            // 左边界可能截断旧U^n/A^m块；只丢第一块，不清空后续可靠交替记录。
            String firstRole = rows.get(0).getRole();
            while (boundarySkipped < rows.size() && rows.get(boundarySkipped).getTurnId() == null
                    && firstRole.equals(rows.get(boundarySkipped).getRole())) boundarySkipped++;
            if ("user".equals(firstRole)) while (boundarySkipped < rows.size()
                    && rows.get(boundarySkipped).getTurnId() == null && "assistant".equals(rows.get(boundarySkipped).getRole())) boundarySkipped++;
            rows = List.copyOf(rows.subList(boundarySkipped, rows.size()));
        }
        Map<String, ChatMessage> sources = rows.stream().collect(Collectors.toMap(row -> sourceId(row.getId()), row -> row));
        List<String> warnings = new ArrayList<>();
        if (scanned == 400) warnings.add("本次仅扫描最近400条消息；更早内容只可通过已保存且验证过的摘记或原文ID恢复。");
        if (boundarySkipped > 0) warnings.add("已跳过扫描左边界无法确认配对的旧消息，未猜测轮次。");
        List<MemoryFragment> carried = new ArrayList<>();
        var checkpoint = checkpoints.get(user, project, session);
        long priorThrough = checkpoint.map(row -> row.getThroughMessageId()).orElse(0L);
        boolean repairCheckpoint = false;
        if (checkpoint.isPresent()) {
            try {
                var saved = json.readValue(checkpoint.get().getNotesJson(), new TypeReference<List<MemoryFragment>>() {});
                var hashes = json.readValue(checkpoint.get().getSourceHashesJson(), new TypeReference<Map<String, String>>() {});
                if (saved == null || hashes == null || saved.size() > 32 || saved.stream().anyMatch(Objects::isNull)
                        || priorThrough < 1) throw new IllegalArgumentException("Invalid checkpoint shape");
                Set<Long> missing = saved.stream().map(MemoryFragment::sourceId).distinct()
                        .filter(id -> !sources.containsKey(id)).map(MemoryContextService::numericId).collect(Collectors.toSet());
                if (!sources.containsKey(sourceId(priorThrough))) missing.add(priorThrough);
                repository.memorySources(user, project, session, List.copyOf(missing)).forEach(row -> sources.put(sourceId(row.getId()), row));
                var frontier = sources.get(sourceId(priorThrough));
                if (frontier == null || !"assistant".equals(frontier.getRole())) throw new IllegalArgumentException("Invalid checkpoint frontier");
                for (var note : saved) {
                    ChatMessage row = sources.get(note.sourceId());
                    if (numericId(note.sourceId()) <= priorThrough && validNote(note, row) && sourceHash(row).equals(hashes.get(note.sourceId()))) carried.add(note);
                    else warnings.add("检查点原文缺失、变更或位置不匹配，已舍弃对应摘记。");
                }
            } catch (RuntimeException corrupt) {
                if (corrupt instanceof com.bedouyu.audiorag.common.ApiException) throw corrupt;
                carried.clear(); warnings.add("检查点读取或核对未完成，未使用派生摘记；原始会话历史保留。");
                priorThrough = 0; repairCheckpoint = true;
            }
        }
        // ordinal是本次所有真实source的相对时序，不能沿用旧窗口ordinal导致新更正排到旧约束前。
        Map<String, Integer> order = new HashMap<>();
        List<String> orderedIds = sources.keySet().stream().sorted(Comparator.comparingLong(MemoryContextService::numericId)).toList();
        for (int i = 0; i < orderedIds.size(); i++) order.put(orderedIds.get(i), i);
        List<MemoryMessage> history = rows.stream().map(row -> new MemoryMessage(sourceId(row.getId()),
                row.getRole(), row.getContent(), order.get(sourceId(row.getId())), row.getTurnId())).toList();
        carried = carried.stream().map(note -> reordinal(note, order.get(note.sourceId()))).collect(Collectors.toCollection(ArrayList::new));
        // 只从已完成轮抽取检查点。孤儿请求可被查询读取，但不能伪装成已完成任务状态。
        Set<String> completeIds = completedSourceIds(rows);
        carried.addAll(engine.extractNotes(history.stream().filter(message -> completeIds.contains(message.id())).toList()));
        List<MemoryFragment> notes = mergeNotes(carried);
        long through = rows.stream().filter(row -> "assistant".equals(row.getRole()) && completeIds.contains(sourceId(row.getId())))
                .mapToLong(ChatMessage::getId).max().orElse(priorThrough);
        if (saveCheckpoint && through >= priorThrough && through > 0) {
            Map<String, String> hashes = new TreeMap<>();
            notes.forEach(note -> hashes.put(note.sourceId(), sourceHash(sources.get(note.sourceId()))));
            try { checkpoints.save(user, project, session, through, json.writeValueAsString(notes), json.writeValueAsString(hashes), repairCheckpoint); }
            catch (RuntimeException unavailable) { warnings.add("检查点未能保存；本轮使用已校验原文，原始聊天记录未被覆盖。"); }
        }
        warnings = warnings.stream().distinct().limit(3).toList();
        int packingBudget = Math.max(256, budget - 400);
        MemoryPack pack = engine.pack(strategy, history, notes, query, packingBudget);
        Prepared prepared = new Prepared(pack, scanned, through, checkpoint.isPresent() && !repairCheckpoint, warnings, budget);
        // 包含状态元数据的最终JSON也纳入预算；不能只数正文。
        for (int retry = 0; retry < 3 && json.writeValueAsString(prepared.modelData()).length() > budget; retry++) {
            int excess = json.writeValueAsString(prepared.modelData()).length() - budget;
            packingBudget = Math.max(256, packingBudget - excess - 16);
            pack = engine.pack(strategy, history, notes, query, packingBudget);
            prepared = new Prepared(pack, scanned, through, checkpoint.isPresent() && !repairCheckpoint, warnings, budget);
        }
        if (json.writeValueAsString(prepared.modelData()).length() > budget)
            throw new IllegalStateException("Memory metadata exceeds budget; enlarge memory budget");
        return prepared;
    }

    public Map<String, Object> read(long user, long project, String session, String id, int start, int maximum) {
        if (start < 0 || maximum < 1 || maximum > 2500) throw new IllegalArgumentException("Memory read range invalid");
        var found = repository.memorySources(user, project, session, List.of(numericId(id)));
        if (found.isEmpty()) throw new IllegalArgumentException("Memory source not found in current session");
        var row = found.get(0);
        if (!session.equals(row.getSessionId()) || row.getId() != numericId(id))
            throw new IllegalArgumentException("Memory source not found in current session");
        if (start > row.getContent().length() || splitSurrogate(row.getContent(), start))
            throw new IllegalArgumentException("Memory start offset invalid");
        int end = Math.min(row.getContent().length(), start + maximum);
        if (splitSurrogate(row.getContent(), end)) end--;
        if (end == start && start < row.getContent().length()) throw new IllegalArgumentException("Range cannot fit one complete Unicode character");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source_id", id); result.put("role", row.getRole()); result.put("turn_id", row.getTurnId() == null ? "" : row.getTurnId());
        result.put("start", start); result.put("end", end); result.put("text", row.getContent().substring(start, end));
        result.put("has_more", end < row.getContent().length()); result.put("source_hash", sourceHash(row));
        result.put("status", "OK");
        while (json.writeValueAsString(result).length() > 7000) {
            end = start + (end - start) / 2;
            if (splitSurrogate(row.getContent(), end)) end--;
            if (end <= start) throw new IllegalArgumentException("Memory source metadata exceeds observation budget");
            result.put("end", end); result.put("text", row.getContent().substring(start, end));
            result.put("has_more", end < row.getContent().length());
        }
        return result;
    }
    private static void validate(String strategy, int budget) {
        if (!STRATEGIES.contains(strategy)) throw new IllegalArgumentException("Unsupported memory strategy: " + strategy);
        if (budget < 1000 || budget > 12000) throw new IllegalArgumentException("Memory context budget is 1000..12000 characters");
    }
    private static String sourceId(long id) { return "m" + id; }
    private static long numericId(String source) {
        if (source == null || !source.matches("m[1-9][0-9]{0,18}")) throw new IllegalArgumentException("Memory source ID invalid");
        return Long.parseLong(source.substring(1));
    }
    private static String sourceHash(ChatMessage row) {
        return Hashing.sha256(row.getRole() + "\n" + (row.getTurnId() == null ? "" : row.getTurnId()) + "\n" + row.getContent());
    }
    private static boolean validNote(MemoryFragment note, ChatMessage row) {
        return row != null && "user".equals(row.getRole()) && "user".equals(note.role()) && "NOTE".equals(note.kind())
                && Objects.equals(note.turnId(), row.getTurnId()) && note.text().length() <= 300
                && note.start() >= 0 && note.end() >= note.start() && note.end() <= row.getContent().length()
                && row.getContent().substring(note.start(), note.end()).equals(note.text())
                && !splitSurrogate(row.getContent(), note.start()) && !splitSurrogate(row.getContent(), note.end());
    }
    private static boolean splitSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset));
    }
    private static MemoryFragment reordinal(MemoryFragment note, int ordinal) {
        return new MemoryFragment(note.sourceId(), note.role(), note.start(), note.end(), note.text(), note.kind(), ordinal, note.turnId());
    }
    private static List<MemoryFragment> mergeNotes(List<MemoryFragment> notes) {
        Map<String, MemoryFragment> unique = new LinkedHashMap<>();
        notes.stream().sorted(Comparator.comparingInt(MemoryFragment::ordinal).reversed().thenComparingInt(MemoryFragment::start))
                .forEach(note -> unique.putIfAbsent(note.sourceId() + ":" + note.start() + ":" + note.end(), note));
        return unique.values().stream().limit(32).sorted(Comparator.comparingInt(MemoryFragment::ordinal)
                .thenComparingInt(MemoryFragment::start)).toList();
    }
    private static Set<String> completedSourceIds(List<ChatMessage> rows) {
        Set<String> result = new HashSet<>();
        Map<String, List<ChatMessage>> identified = rows.stream().filter(row -> row.getTurnId() != null)
                .collect(Collectors.groupingBy(ChatMessage::getTurnId));
        identified.values().forEach(turn -> {
            if (turn.size() == 2 && "user".equals(turn.get(0).getRole()) && "assistant".equals(turn.get(1).getRole()))
                turn.forEach(row -> result.add(sourceId(row.getId())));
        });
        // 旧记录只接受邻近的明确U,A，两边出现额外同role则视为歧义，不猜配对。
        for (int i = 0; i + 1 < rows.size(); i++) {
            var u = rows.get(i); var a = rows.get(i + 1);
            if (u.getTurnId() != null || a.getTurnId() != null || !"user".equals(u.getRole()) || !"assistant".equals(a.getRole())) continue;
            if (i > 0 && rows.get(i - 1).getTurnId() == null && "user".equals(rows.get(i - 1).getRole())) continue;
            if (i + 2 < rows.size() && rows.get(i + 2).getTurnId() == null && "assistant".equals(rows.get(i + 2).getRole())) continue;
            result.add(sourceId(u.getId())); result.add(sourceId(a.getId())); i++;
        }
        return result;
    }
    public record Prepared(MemoryPack pack, int scannedMessages, long checkpointThroughId, boolean checkpointLoaded,
                           List<String> checkpointWarnings, int totalBudgetChars) {
        public Map<String, Object> modelData() {
            Map<String, Object> value = new LinkedHashMap<>(pack.modelData());
            value.put("scanned_messages", scannedMessages); value.put("checkpoint_through_id", checkpointThroughId);
            value.put("checkpoint_loaded", checkpointLoaded); value.put("checkpoint_warnings", checkpointWarnings);
            value.put("total_budget_chars", totalBudgetChars);
            value.put("notes_type", "source-linked-extractive-notes-not-llm-summary");
            return value;
        }
    }
}
