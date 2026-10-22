package com.bedouyu.audiorag.memory.strategy;

import com.bedouyu.audiorag.rag.strategy.Bm25Ranker;
import com.bedouyu.audiorag.rag.strategy.TextSegment;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure, deterministic selection of persisted original text. Extractive notes use lexical
 * heuristics, not an LLM summary, semantic memory, factual validation, or correction resolution.
 * A recent user→assistant turn with a shared turn identity is admitted together. Legacy
 * messages must form an unambiguous alternating block. If even the newest turn is too large,
 * RECENT_EXCERPT marks explicit original ranges; RECENT_TURN_PARTIAL means only its user fits.
 */
public final class MemoryStrategyEngine {
    private static final Set<String> STRATEGIES = Set.of("recent_window", "query_recall", "extractive_notes", "hybrid");
    private static final Pattern NOTE_MARKER = Pattern.compile(
            "目标|需求|约束|不需要|改为|更正|取消|必须|不要|希望|改成|不再|限制|要求|预算|deadline|must|need|want|instead|correction|cancel|actually",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CORRECTION = Pattern.compile(
            "改为|更正|取消|改成|不再|变更|修改|instead|correction|cancel|actually", Pattern.CASE_INSENSITIVE);
    private static final Comparator<MemoryFragment> CHRONOLOGICAL = Comparator
            .comparingInt(MemoryFragment::ordinal).thenComparingInt(MemoryFragment::start)
            .thenComparing(MemoryFragment::sourceId).thenComparing(MemoryFragment::kind);
    private static final Comparator<MemoryFragment> NOTE_PRIORITY = Comparator
            .comparing((MemoryFragment fragment) -> CORRECTION.matcher(fragment.text()).find()).reversed()
            .thenComparing(Comparator.comparingInt(MemoryFragment::ordinal).reversed())
            .thenComparing(Comparator.comparingInt(MemoryFragment::start).reversed())
            .thenComparing(MemoryFragment::sourceId);
    private final ObjectMapper json;

    public MemoryStrategyEngine(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public MemoryPack pack(String strategy, List<MemoryMessage> chronologicalHistory,
                           List<MemoryFragment> checkpointNotes, String query, int budgetChars) {
        if (!STRATEGIES.contains(strategy)) throw new IllegalArgumentException("Unknown memory strategy: " + strategy);
        if (budgetChars < 256 || budgetChars > 12000) throw new IllegalArgumentException("Memory budget must be 256..12000");
        List<MemoryMessage> history = publicHistory(chronologicalHistory);
        RecentSelection selection = recentGroups(history, strategy.equals("recent_window") ? Integer.MAX_VALUE : 2);
        List<List<MemoryMessage>> groups = selection.groups();
        Set<String> recentIds = new HashSet<>();
        groups.forEach(group -> group.forEach(message -> recentIds.add(message.id())));
        List<MemoryFragment> notes = strategy.equals("extractive_notes") || strategy.equals("hybrid")
                ? noteCandidates(history, checkpointNotes) : List.of();
        List<MemoryFragment> recalled = strategy.equals("query_recall") || strategy.equals("hybrid")
                ? recall(history, recentIds, expandedQuery(query, history)) : List.of();
        Budget builder = new Budget(strategy, budgetChars);
        builder.warnings.addAll(selection.warnings());
        int baseline = builder.characters();
        boolean otherCandidates = !notes.isEmpty() || !recalled.isEmpty();
        int recentLimit = otherCandidates ? baseline + (budgetChars - baseline) / 3 : budgetChars;
        // A complete newest turn has priority over route shares whenever its actual JSON fits.
        // The second recent turn is still subject to the soft share and remaining total budget.
        List<MemoryMessage> latestCompleted = groups.stream().filter(group -> group.size() == 2).findFirst().orElse(null);
        if (latestCompleted != null) {
            int latestTurnCost = builder.sizeWith(latestCompleted.stream()
                    .map(message -> fragment(message, 0, message.content().length(), "RECENT")).toList());
            if (latestTurnCost <= budgetChars) recentLimit = Math.max(recentLimit, latestTurnCost);
        }
        addRecent(builder, groups, recentLimit);
        int routeAllowance = Math.max(0, (budgetChars - baseline) / 3);
        int notesLimit = strategy.equals("hybrid") && !recalled.isEmpty()
                ? Math.min(budgetChars, builder.characters() + routeAllowance) : budgetChars;
        addCandidates(builder, notes, notesLimit);
        addCandidates(builder, recalled, budgetChars);
        // Reuse unspent quota; candidate priority is stable and shared total JSON budget remains exact.
        addCandidates(builder, notes, budgetChars);
        addRecent(builder, groups, budgetChars);
        return builder.result();
    }

    /**
     * Select up to 32 user quotations, each at most 300 UTF-16 characters. Correction words get
     * priority, newest first. This is a transparent lexical heuristic; it does not decide which
     * statement is true, synthesize facts, or semantically reconcile contradictory statements.
     */
    public List<MemoryFragment> extractNotes(List<MemoryMessage> chronologicalHistory) {
        List<MemoryFragment> candidates = new ArrayList<>();
        for (MemoryMessage message : publicHistory(chronologicalHistory)) {
            if (!message.role().equals("user")) continue;
            for (int[] range : sentenceRanges(message.content())) {
                String sentence = message.content().substring(range[0], range[1]);
                var marker = NOTE_MARKER.matcher(sentence);
                if (!marker.find()) continue;
                var correction = CORRECTION.matcher(sentence);
                int markerOffset = correction.find() ? correction.start() : marker.start();
                int start = range[0];
                if (range[1] - start > 300 && markerOffset >= 220)
                    start = safeStart(message.content(), range[0] + Math.max(0, markerOffset - 80));
                int end = safeEnd(message.content(), Math.min(range[1], start + 300));
                if (end > start) candidates.add(fragment(message, start, end, "NOTE"));
            }
        }
        return candidates.stream().sorted(NOTE_PRIORITY).limit(32).sorted(CHRONOLOGICAL).toList();
    }

    private List<MemoryFragment> noteCandidates(List<MemoryMessage> history, List<MemoryFragment> checkpoints) {
        Map<String, MemoryMessage> sources = new HashMap<>();
        history.forEach(message -> sources.put(message.id(), message));
        Map<String, MemoryFragment> unique = new LinkedHashMap<>();
        for (MemoryFragment note : Objects.requireNonNull(checkpoints, "checkpointNotes")) {
            if (!note.role().equals("user")) continue;
            MemoryMessage source = sources.get(note.sourceId());
            // Older checkpoints are validated against persisted source/hash/ranges by the service.
            // Do not require an older source to be present in this recent-history window.
            if (source != null && (!source.role().equals(note.role()) || source.ordinal() != note.ordinal()
                    || note.turnId() != null && !Objects.equals(source.turnId(), note.turnId())
                    || note.end() > source.content().length()
                    || !source.content().substring(note.start(), note.end()).equals(note.text())))
                throw new IllegalArgumentException("Checkpoint note does not match its original message");
            int end = safeEnd(note.text(), Math.min(300, note.text().length()));
            if (end == 0) continue;
            MemoryFragment normalized = new MemoryFragment(note.sourceId(), note.role(), note.start(),
                    note.start() + end, note.text().substring(0, end), "NOTE", note.ordinal(),
                    source == null ? note.turnId() : source.turnId());
            unique.putIfAbsent(rangeKey(normalized), normalized);
        }
        // The caller decides which completed-history snapshot to extract and persist. pack()
        // only consumes the supplied, verified checkpoint quotations and never extracts query text.
        return unique.values().stream().sorted(NOTE_PRIORITY).toList();
    }

    private List<MemoryFragment> recall(List<MemoryMessage> history, Set<String> recentIds, String query) {
        if (query.isBlank()) return List.of();
        List<TextSegment> segments = new ArrayList<>();
        Map<String, MemoryFragment> identities = new HashMap<>();
        int chunkOrdinal = 0;
        for (MemoryMessage message : history) {
            if (recentIds.contains(message.id())) continue;
            for (int start = 0; start < message.content().length();) {
                int end = safeEnd(message.content(), Math.min(message.content().length(), start + 600));
                String text = message.content().substring(start, end);
                TextSegment segment = new TextSegment(message.id(), chunkOrdinal++, start, end, text, 0);
                segments.add(segment);
                identities.put(segment.id(), fragment(message, start, end, "RECALL"));
                start = end;
            }
        }
        return new Bm25Ranker(segments).rank(query, segments.size()).stream()
                .map(hit -> identities.get(hit.chunk().id())).toList();
    }

    private static String expandedQuery(String query, List<MemoryMessage> history) {
        String supplied = query == null ? "" : query.strip();
        if (!isContinuation(supplied)) return supplied;
        for (int index = history.size() - 1; index >= 0; index--) {
            MemoryMessage message = history.get(index);
            if (message.role().equals("user") && !message.content().isBlank() && !isContinuation(message.content()))
                return supplied + " " + message.content();
        }
        return supplied;
    }

    private static boolean isContinuation(String value) {
        String compact = value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]+", "");
        return Set.of("继续", "下一步", "接着", "然后呢", "再说说", "继续吧", "好", "好的", "continue", "next", "goon", "ok")
                .contains(compact);
    }

    private static List<MemoryMessage> publicHistory(List<MemoryMessage> supplied) {
        Map<String, MemoryMessage> messages = new LinkedHashMap<>();
        for (MemoryMessage message : Objects.requireNonNull(supplied, "chronologicalHistory")) {
            if (!message.role().equals("user") && !message.role().equals("assistant")) continue;
            MemoryMessage previous = messages.putIfAbsent(message.id(), message);
            if (previous != null && !previous.equals(message))
                throw new IllegalArgumentException("One source identity refers to different original messages");
        }
        return messages.values().stream().sorted(Comparator.comparingInt(MemoryMessage::ordinal)).toList();
    }

    /** A shared persisted turn ID remains reliable even when concurrent turns interleave. */
    private static RecentSelection recentGroups(List<MemoryMessage> history, int completedLimit) {
        List<List<MemoryMessage>> groups = new ArrayList<>();
        Set<String> warnings = new LinkedHashSet<>();
        Map<String, List<MemoryMessage>> byTurn = new LinkedHashMap<>();
        for (MemoryMessage message : history) {
            if (message.turnId() != null) byTurn.computeIfAbsent(message.turnId(), ignored -> new ArrayList<>()).add(message);
        }
        for (List<MemoryMessage> turn : byTurn.values()) {
            if (turn.size() == 2 && turn.get(0).role().equals("user") && turn.get(1).role().equals("assistant"))
                groups.add(List.copyOf(turn));
            else if (turn.size() == 1 && turn.get(0).role().equals("user")
                    && turn.get(0).equals(history.get(history.size() - 1))) groups.add(List.copyOf(turn));
            else if (turn.size() > 1) warnings.add("AMBIGUOUS_TURN_ID");
        }
        // A legacy block is U^n followed by A^m. Only n=m=1 proves an alternating turn;
        // U,U,A,A (or either unequal run) is ambiguous and must never pair the middle U/A.
        for (int index = 0; index < history.size();) {
            if (history.get(index).turnId() != null) { index++; continue; }
            int runEnd = index;
            while (runEnd < history.size() && history.get(runEnd).turnId() == null) runEnd++;
            while (index < runEnd) {
                if (history.get(index).role().equals("assistant")) { index++; continue; }
                int usersStart = index;
                while (index < runEnd && history.get(index).role().equals("user")) index++;
                int assistantsStart = index;
                while (index < runEnd && history.get(index).role().equals("assistant")) index++;
                int userCount = assistantsStart - usersStart;
                int assistantCount = index - assistantsStart;
                if (userCount == 1 && assistantCount == 1)
                    groups.add(List.of(history.get(usersStart), history.get(assistantsStart)));
                else if (userCount == 1 && assistantCount == 0 && index == history.size())
                    groups.add(List.of(history.get(usersStart)));
                else warnings.add("AMBIGUOUS_LEGACY_TURNS");
            }
        }
        groups.sort(Comparator.comparingInt(group -> group.get(group.size() - 1).ordinal()));
        List<List<MemoryMessage>> newestFirst = new ArrayList<>();
        int completed = 0;
        for (int index = groups.size() - 1; index >= 0; index--) {
            List<MemoryMessage> group = groups.get(index);
            if (group.size() == 2 && completed++ >= completedLimit) break;
            newestFirst.add(group);
        }
        // A pending tail user remains a real standalone message, but cannot displace a latest
        // completed turn that fits. Final envelope order still follows original ordinals.
        for (int index = 0; index < newestFirst.size(); index++) {
            if (newestFirst.get(index).size() == 2) {
                if (index > 0) newestFirst.add(0, newestFirst.remove(index));
                break;
            }
        }
        return new RecentSelection(List.copyOf(newestFirst), List.copyOf(warnings));
    }

    private record RecentSelection(List<List<MemoryMessage>> groups, List<String> warnings) { }

    private void addRecent(Budget builder, List<List<MemoryMessage>> newestFirst, int limit) {
        for (List<MemoryMessage> group : newestFirst) {
            List<MemoryFragment> full = group.stream()
                    .map(message -> fragment(message, 0, message.content().length(), "RECENT")).toList();
            if (builder.alreadyContains(full)) continue;
            if (builder.overlapsAny(full)) continue;
            if (builder.tryGroup(full, limit)) continue;
            boolean hasRecent = builder.fragments.stream().anyMatch(fragment -> fragment.kind().equals("RECENT"));
            if (!hasRecent) addRecentExcerpts(builder, group, limit);
            break;
        }
    }

    private void addRecentExcerpts(Budget builder, List<MemoryMessage> group, int routeLimit) {
        builder.warnings.add("RECENT_EXCERPT");
        List<MemoryMessage> nonempty = group.stream().filter(message -> !message.content().isEmpty()).toList();
        if (nonempty.isEmpty()) return;
        List<MemoryFragment> minimum = excerpts(nonempty, 1);
        // At small budgets the provenance envelope itself may exceed a route's nominal third.
        int limit = builder.sizeWith(minimum) <= routeLimit ? routeLimit
                : Math.min(builder.budgetChars, Math.max(routeLimit, builder.sizeWith(minimum)));
        if (builder.sizeWith(minimum) > limit && nonempty.size() == 2) {
            builder.warnings.add("RECENT_TURN_PARTIAL");
            nonempty = List.of(nonempty.get(0)); // Preserve the actual user; never invent a partner.
            minimum = excerpts(nonempty, 1);
            limit = builder.budgetChars;
        }
        if (builder.sizeWith(minimum) > limit) {
            builder.warnings.add("SOURCE_METADATA_EXCEEDS_BUDGET");
            return;
        }
        int low = 1;
        int high = nonempty.stream().mapToInt(message -> message.content().length()).max().orElse(1);
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (builder.sizeWith(excerpts(nonempty, middle)) <= limit) low = middle;
            else high = middle - 1;
        }
        builder.tryGroup(excerpts(nonempty, low), limit);
    }

    private static List<MemoryFragment> excerpts(List<MemoryMessage> group, int quoteChars) {
        return group.stream().map(message -> {
            int end = safeEnd(message.content(), Math.min(message.content().length(), quoteChars));
            if (end == 0) end = Math.min(2, message.content().length());
            return fragment(message, 0, end, "RECENT");
        }).toList();
    }

    private void addCandidates(Budget builder, List<MemoryFragment> candidates, int limit) {
        for (MemoryFragment candidate : candidates) {
            if (builder.overlapsAny(List.of(candidate))) continue;
            if (builder.tryGroup(List.of(candidate), limit)) continue;
            // Recall may use a smaller original excerpt; note quotations remain intact.
            if (!candidate.kind().equals("RECALL")) continue;
            int low = 0;
            int high = candidate.text().length();
            while (low < high) {
                int middle = low + (high - low + 1) / 2;
                int end = safeEnd(candidate.text(), middle);
                MemoryFragment clipped = new MemoryFragment(candidate.sourceId(), candidate.role(), candidate.start(),
                        candidate.start() + end, candidate.text().substring(0, end), candidate.kind(), candidate.ordinal(), candidate.turnId());
                if (builder.sizeWith(List.of(clipped)) <= limit) low = middle;
                else high = middle - 1;
            }
            int end = safeEnd(candidate.text(), low);
            if (end > 0) builder.tryGroup(List.of(new MemoryFragment(candidate.sourceId(), candidate.role(), candidate.start(),
                    candidate.start() + end, candidate.text().substring(0, end), candidate.kind(), candidate.ordinal(), candidate.turnId())), limit);
        }
    }

    private static List<int[]> sentenceRanges(String text) {
        List<int[]> ranges = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            if ("。！？；!?;\n\r".indexOf(text.charAt(index)) >= 0) {
                addTrimmedRange(ranges, text, start, index + 1);
                start = index + 1;
            }
        }
        addTrimmedRange(ranges, text, start, text.length());
        return ranges;
    }

    private static void addTrimmedRange(List<int[]> ranges, String text, int start, int end) {
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        if (end > start) ranges.add(new int[]{start, end});
    }

    private static int safeEnd(String text, int end) {
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) return end - 1;
        return end;
    }

    private static int safeStart(String text, int start) {
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))
                && Character.isHighSurrogate(text.charAt(start - 1))) return start - 1;
        return start;
    }

    private static MemoryFragment fragment(MemoryMessage source, int start, int end, String kind) {
        return new MemoryFragment(source.id(), source.role(), start, end, source.content().substring(start, end), kind, source.ordinal(), source.turnId());
    }

    private static String rangeKey(MemoryFragment fragment) {
        return fragment.sourceId() + ":" + fragment.start() + ":" + fragment.end();
    }

    private final class Budget {
        private final String strategy;
        private final int budgetChars;
        private final List<MemoryFragment> fragments = new ArrayList<>();
        private final Set<String> warnings = new LinkedHashSet<>();

        private Budget(String strategy, int budgetChars) {
            this.strategy = strategy;
            this.budgetChars = budgetChars;
        }

        private MemoryPack result() {
            MemoryPack result = new MemoryPack(strategy, fragments.stream().sorted(CHRONOLOGICAL).toList(),
                    budgetChars, List.copyOf(warnings));
            if (result.characters(json) > budgetChars) throw new IllegalStateException("Memory JSON budget exceeded");
            return result;
        }

        private int characters() {
            return new MemoryPack(strategy, fragments, budgetChars, List.copyOf(warnings)).characters(json);
        }

        private int sizeWith(List<MemoryFragment> additions) {
            List<MemoryFragment> all = new ArrayList<>(fragments);
            all.addAll(additions);
            return new MemoryPack(strategy, all, budgetChars, List.copyOf(warnings)).characters(json);
        }

        private boolean tryGroup(List<MemoryFragment> additions, int limit) {
            if (overlapsAny(additions) || sizeWith(additions) > Math.min(limit, budgetChars)) return false;
            fragments.addAll(additions);
            return true;
        }

        private boolean alreadyContains(List<MemoryFragment> candidates) {
            return candidates.stream().allMatch(candidate -> fragments.contains(candidate));
        }

        private boolean overlapsAny(List<MemoryFragment> candidates) {
            return candidates.stream().anyMatch(candidate -> fragments.stream().anyMatch(existing ->
                    existing.sourceId().equals(candidate.sourceId())
                            && (existing.start() < candidate.end() && candidate.start() < existing.end()
                            || existing.start() == candidate.start() && existing.end() == candidate.end())));
        }
    }
}
