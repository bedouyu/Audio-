package com.bedouyu.audiorag.rag.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** Windows bounded by untruncated content tokens, excluding CLS/SEP. */
public final class TokenWindowChunker implements ChunkingStrategy {
    private final int maxTokens;
    private final int overlapTokens;

    public TokenWindowChunker() {
        this(256, 32);
    }

    public TokenWindowChunker(int maxTokens, int overlapTokens) {
        validateLimits(maxTokens, overlapTokens);
        this.maxTokens = maxTokens;
        this.overlapTokens = overlapTokens;
    }

    public int maxTokens() {
        return maxTokens;
    }

    public int overlapTokens() {
        return overlapTokens;
    }

    @Override
    public String id() {
        return "token_" + maxTokens + "_" + overlapTokens;
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
            int end = fittingEnd(text, start, maxTokens, contentTokenCount);
            String content = text.substring(start, end);
            result.add(new TextSegment(documentId, result.size(), start, end, content,
                    count(contentTokenCount, content)));
            if (end == text.length()) {
                break;
            }
            start = overlapStart(text, start, end, overlapTokens, contentTokenCount);
        }
        return List.copyOf(result);
    }

    static void validateLimits(int maxTokens, int overlapTokens) {
        if (maxTokens <= 0 || overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("Require maxTokens > overlapTokens >= 0");
        }
    }

    static int count(ToIntFunction<String> counter, String content) {
        int tokens = counter.applyAsInt(content);
        if (tokens < 0) {
            throw new IllegalArgumentException("Content token count must be nonnegative");
        }
        return tokens;
    }

    /** Find a verified fitting prefix without repeatedly tokenizing the whole document. */
    static int fittingEnd(String text, int start, int limit, ToIntFunction<String> counter) {
        int fitting = start;
        int probe = safeBefore(text, (int) Math.min(text.length(), (long) start + limit));
        if (probe == start) {
            probe = nextCodePoint(text, start);
        }
        while (count(counter, text.substring(start, probe)) <= limit) {
            fitting = probe;
            if (probe == text.length()) {
                return probe;
            }
            long distance = Math.max(2L, 2L * (probe - start));
            probe = safeBefore(text, (int) Math.min(text.length(), start + distance));
            if (probe <= fitting) {
                probe = nextCodePoint(text, fitting);
            }
        }

        int failing = probe;
        while (failing - fitting > 1) {
            int middle = safeBefore(text, fitting + (failing - fitting) / 2);
            if (middle == fitting) {
                break;
            }
            if (count(counter, text.substring(start, middle)) <= limit) {
                fitting = middle;
            } else {
                failing = middle;
            }
        }
        if (fitting == start) {
            throw new IllegalArgumentException("A source code point exceeds the content token limit at " + start);
        }
        // Tokenization of prefixes need not be monotonic. Only return a prefix
        // whose own count was checked; maximality is not needed for coverage.
        return fitting;
    }

    /** Select a verified suffix within the overlap budget, always advancing. */
    static int overlapStart(String text, int start, int end, int limit,
                            ToIntFunction<String> counter) {
        int first = nextCodePoint(text, start);
        if (count(counter, text.substring(first, end)) <= limit) {
            return first;
        }
        int failing = first;
        int fitting = end;
        while (fitting - failing > 1) {
            int middle = safeBefore(text, failing + (fitting - failing) / 2);
            if (middle == failing) {
                break;
            }
            if (count(counter, text.substring(middle, end)) <= limit) {
                fitting = middle;
            } else {
                failing = middle;
            }
        }
        return fitting;
    }

    static int nextCodePoint(String text, int offset) {
        return offset + Character.charCount(text.codePointAt(offset));
    }

    static int safeBefore(String text, int offset) {
        return splitsSurrogate(text, offset) ? offset - 1 : offset;
    }

    static int safeAfter(String text, int offset) {
        return splitsSurrogate(text, offset) ? offset + 1 : offset;
    }

    private static boolean splitsSurrogate(String text, int offset) {
        return offset > 0 && offset < text.length()
                && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset));
    }
}
