package com.bedouyu.audiorag.memory.strategy;

import java.util.Objects;

/** Original UTF-16 range [start,end); text is never a paraphrase or generated summary. */
public record MemoryFragment(String sourceId, String role, int start, int end,
                             String text, String kind, int ordinal, String turnId) {
    public MemoryFragment(String sourceId, String role, int start, int end, String text, String kind, int ordinal) {
        this(sourceId, role, start, end, text, kind, ordinal, null);
    }

    public MemoryFragment {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(kind, "kind");
        if (sourceId.isBlank() || start < 0 || end < start || end - start != text.length() || ordinal < 0)
            throw new IllegalArgumentException("Invalid original-text range or identity");
        if (turnId != null && turnId.isBlank()) throw new IllegalArgumentException("Blank turn identity");
        if (!kind.equals("RECENT") && !kind.equals("RECALL") && !kind.equals("NOTE"))
            throw new IllegalArgumentException("Unknown memory fragment kind: " + kind);
        if ((!text.isEmpty() && Character.isLowSurrogate(text.charAt(0)))
                || (!text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1))))
            throw new IllegalArgumentException("Fragment cuts a Unicode surrogate pair");
    }
}
