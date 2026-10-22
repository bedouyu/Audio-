package com.bedouyu.audiorag.rag.strategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** Token-bounded chunks preferring paragraph and sentence boundaries. */
public final class SentenceTokenChunker implements ChunkingStrategy {
    private final int maxTokens;
    private final int overlapTokens;

    public SentenceTokenChunker() {
        this(384, 48);
    }

    public SentenceTokenChunker(int maxTokens, int overlapTokens) {
        TokenWindowChunker.validateLimits(maxTokens, overlapTokens);
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
        return "sentence_" + maxTokens + "_" + overlapTokens;
    }

    @Override
    public List<TextSegment> split(String documentId, String text,
                                   ToIntFunction<String> contentTokenCount) {
        Objects.requireNonNull(contentTokenCount, "contentTokenCount");
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Boundaries boundaries = boundaries(text);
        List<TextSegment> result = new ArrayList<>();
        int start = 0;
        int coveredEnd = 0;
        while (start < text.length()) {
            int hardEnd = TokenWindowChunker.fittingEnd(text, start, maxTokens, contentTokenCount);
            int end = hardEnd;
            if (hardEnd < text.length()) {
                int paragraphEnd = fittingBoundary(boundaries.paragraphs(), text, start, coveredEnd,
                        hardEnd, contentTokenCount);
                int sentenceEnd = paragraphEnd < 0
                        ? fittingBoundary(boundaries.sentences(), text, start, coveredEnd,
                                hardEnd, contentTokenCount)
                        : paragraphEnd;
                if (sentenceEnd >= 0) {
                    end = sentenceEnd;
                }
            }
            String content = text.substring(start, end);
            result.add(new TextSegment(documentId, result.size(), start, end, content,
                    TokenWindowChunker.count(contentTokenCount, content)));
            coveredEnd = Math.max(coveredEnd, end);
            if (end == text.length()) {
                break;
            }
            int overlapStart = TokenWindowChunker.overlapStart(text, start, end,
                    overlapTokens, contentTokenCount);
            int nextStart = overlapBoundary(boundaries.paragraphs(), text, overlapStart, end,
                    contentTokenCount);
            if (nextStart < 0) {
                nextStart = overlapBoundary(boundaries.sentences(), text, overlapStart, end,
                        contentTokenCount);
            }
            // A complete preceding sentence may exceed the overlap budget.
            // In that case begin at its end. Long sentences still use token overlap.
            if (nextStart < 0 && isBoundary(boundaries, end)) {
                nextStart = end;
            }
            start = nextStart < 0 ? overlapStart : nextStart;
        }
        return List.copyOf(result);
    }

    private int fittingBoundary(List<Integer> candidates, String text, int start,
                                int coveredEnd, int hardEnd, ToIntFunction<String> counter) {
        int index = lastAtMost(candidates, hardEnd);
        for (; index >= 0; index--) {
            int boundary = candidates.get(index);
            if (boundary <= Math.max(start, coveredEnd)) {
                break;
            }
            if (TokenWindowChunker.count(counter, text.substring(start, boundary)) <= maxTokens) {
                return boundary;
            }
        }
        return -1;
    }

    private int overlapBoundary(List<Integer> candidates, String text, int first, int end,
                                ToIntFunction<String> counter) {
        int index = lastAtMost(candidates, first - 1) + 1;
        for (; index < candidates.size(); index++) {
            int boundary = candidates.get(index);
            if (boundary >= end) {
                break;
            }
            if (TokenWindowChunker.count(counter, text.substring(boundary, end)) <= overlapTokens) {
                return boundary;
            }
        }
        return -1;
    }

    private static boolean isBoundary(Boundaries boundaries, int offset) {
        return java.util.Collections.binarySearch(boundaries.paragraphs(), offset) >= 0
                || java.util.Collections.binarySearch(boundaries.sentences(), offset) >= 0;
    }

    private static int lastAtMost(List<Integer> candidates, int offset) {
        int index = java.util.Collections.binarySearch(candidates, offset);
        return index >= 0 ? index : -index - 2;
    }

    private static Boundaries boundaries(String text) {
        List<Integer> paragraphs = new ArrayList<>();
        List<Integer> sentences = new ArrayList<>();
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\r' || value == '\n') {
                int end = index + 1;
                if (value == '\r' && end < text.length() && text.charAt(end) == '\n') {
                    end++;
                }
                paragraphs.add(end);
                index = end - 1;
            } else if (sentencePunctuation(text, index)) {
                int end = index + 1;
                while (end < text.length() && isClosingPunctuation(text.charAt(end))) {
                    end++;
                }
                sentences.add(end);
                index = end - 1;
            }
        }
        return new Boundaries(paragraphs, sentences);
    }

    private static boolean sentencePunctuation(String text, int index) {
        char value = text.charAt(index);
        if (value == '.') {
            // Keep decimals and periods inside ASCII names together.
            return index == 0 || index + 1 == text.length()
                    || !asciiWord(text.charAt(index - 1)) || !asciiWord(text.charAt(index + 1));
        }
        return value == '。' || value == '！' || value == '？' || value == '!' || value == '?'
                || value == '；' || value == ';';
    }

    private static boolean asciiWord(char value) {
        return value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                || value >= '0' && value <= '9';
    }

    private static boolean isClosingPunctuation(char value) {
        return value == '"' || value == '\'' || value == '”' || value == '’'
                || value == ')' || value == '）' || value == '】' || value == '」' || value == '』';
    }

    private record Boundaries(List<Integer> paragraphs, List<Integer> sentences) { }
}
