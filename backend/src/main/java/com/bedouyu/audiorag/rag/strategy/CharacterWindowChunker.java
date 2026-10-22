package com.bedouyu.audiorag.rag.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** The legacy 900/120 character window, retaining exact source ranges. */
public final class CharacterWindowChunker implements ChunkingStrategy {
    private final int chunkSize;
    private final int overlap;

    public CharacterWindowChunker() {
        this(900, 120);
    }

    public CharacterWindowChunker(int chunkSize, int overlap) {
        if (chunkSize <= 0 || overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("Require chunkSize > overlap >= 0");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    public int chunkSize() {
        return chunkSize;
    }

    public int overlap() {
        return overlap;
    }

    @Override
    public String id() {
        return "character_" + chunkSize + "_" + overlap;
    }

    @Override
    public List<TextSegment> split(String documentId, String text,
                                   ToIntFunction<String> contentTokenCount) {
        Objects.requireNonNull(contentTokenCount, "contentTokenCount");
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<TextSegment> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int idealEnd = (int) Math.min(text.length(), (long) start + chunkSize);
            int end = boundary(text, start, idealEnd);
            String content = text.substring(start, end);
            result.add(new TextSegment(documentId, result.size(), start, end, content,
                    TokenWindowChunker.count(contentTokenCount, content)));
            if (end == text.length()) {
                break;
            }
            // Round forward so the overlap never starts in a surrogate pair.
            start = TokenWindowChunker.safeAfter(text, Math.max(start + 1, end - overlap));
        }
        return List.copyOf(result);
    }

    private int boundary(String text, int start, int idealEnd) {
        if (idealEnd == text.length()) {
            return idealEnd;
        }
        int floor = Math.max(start + chunkSize / 2, idealEnd - 180);
        for (int index = idealEnd; index >= floor && index > start; index--) {
            char value = text.charAt(index - 1);
            if (value == '\n' || value == '。' || value == '！' || value == '？' || value == '.') {
                return index;
            }
        }
        int end = TokenWindowChunker.safeBefore(text, idealEnd);
        // A single supplementary code point takes two UTF-16 units.
        return end > start ? end : TokenWindowChunker.nextCodePoint(text, start);
    }
}
