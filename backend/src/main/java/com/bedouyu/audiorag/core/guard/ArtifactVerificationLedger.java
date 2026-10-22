package com.bedouyu.audiorag.core.guard;

import java.util.*;

/** Reading and local checks belong to one exact artifact and content revision. */
public final class ArtifactVerificationLedger {
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public synchronized void read(String id, String rawHash, String renderedHash, int from, int to, int total) {
        if (total < 0 || from < 0 || to < from || to > total)
            throw new IllegalArgumentException("产物阅读范围无效");
        Entry entry = revision(id, rawHash, renderedHash);
        if (entry.totalChars >= 0 && entry.totalChars != total)
            throw new IllegalArgumentException("同一产物内容的总字符数不一致");
        entry.totalChars = total;
        entry.spans.add(new ReadSpan(from, to));
        entry.spans.sort(Comparator.comparingInt(ReadSpan::from));
        List<ReadSpan> merged = new ArrayList<>();
        for (ReadSpan span : entry.spans) {
            if (merged.isEmpty() || merged.get(merged.size() - 1).to() < span.from()) merged.add(span);
            else {
                ReadSpan previous = merged.remove(merged.size() - 1);
                merged.add(new ReadSpan(previous.from(), Math.max(previous.to(), span.to())));
            }
        }
        entry.spans.clear(); entry.spans.addAll(merged);
    }

    public synchronized void check(String id, String rawHash, String renderedHash, boolean passed, List<String> issues) {
        List<String> checkedIssues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (passed && !checkedIssues.isEmpty()) throw new IllegalArgumentException("通过的本机检查不能包含问题");
        Entry entry = revision(id, rawHash, renderedHash);
        entry.checked = true; entry.passed = passed; entry.issues = checkedIssues;
    }

    public synchronized boolean fullRead(String id, String rawHash, String renderedHash) {
        Entry entry = entries.get(id);
        return entry != null && entry.rawHash.equals(rawHash) && entry.renderedHash.equals(renderedHash) && entry.fullRead();
    }

    /** An immutable snapshot; no result exists until a read or a check has actually completed. */
    public synchronized Result result(String id) {
        Entry entry = entries.get(id);
        return entry == null ? null : new Result(id, entry.rawHash, entry.renderedHash, List.copyOf(entry.spans),
                entry.totalChars, entry.fullRead(), entry.checked, entry.passed, entry.issues);
    }

    private Entry revision(String id, String rawHash, String renderedHash) {
        if (id == null || id.isBlank() || rawHash == null || rawHash.isBlank() || renderedHash == null || renderedHash.isBlank())
            throw new IllegalArgumentException("产物编号与内容校验值不能为空");
        Entry current = entries.get(id);
        if (current == null || !current.rawHash.equals(rawHash) || !current.renderedHash.equals(renderedHash)) {
            current = new Entry(rawHash, renderedHash); entries.put(id, current);
        }
        return current;
    }

    public record ReadSpan(int from, int to) { }
    public record Result(String artifactId, String rawHash, String renderedHash, List<ReadSpan> readSpans,
                         int totalChars, boolean readFull, boolean checked, boolean localCheckPassed, List<String> issues) { }

    private static final class Entry {
        final String rawHash, renderedHash;
        final List<ReadSpan> spans = new ArrayList<>();
        int totalChars = -1;
        boolean checked, passed;
        List<String> issues = List.of();
        Entry(String rawHash, String renderedHash) { this.rawHash = rawHash; this.renderedHash = renderedHash; }
        boolean fullRead() {
            return totalChars >= 0 && spans.size() == 1 && spans.get(0).from() == 0 && spans.get(0).to() == totalChars;
        }
    }
}
