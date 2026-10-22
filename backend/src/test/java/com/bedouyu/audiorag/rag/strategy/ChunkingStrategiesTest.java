package com.bedouyu.audiorag.rag.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bedouyu.audiorag.rag.TextChunker;
import java.time.Duration;
import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class ChunkingStrategiesTest {
    private static final ToIntFunction<String> CODE_POINTS =
            text -> text.codePointCount(0, text.length());

    @Test
    void characterWindowsMatchLegacyWindowAndLast180CharacterBoundarySearch() {
        String text = "甲".repeat(750) + "。" + "乙".repeat(220) + "！"
                + "丙".repeat(850) + "。" + "丁".repeat(500);
        List<TextSegment> segments = new CharacterWindowChunker().split("legacy", text, CODE_POINTS);

        assertThat(segments).extracting(TextSegment::text).containsExactlyElementsOf(new TextChunker().split(text));
        assertThat(segments.get(0).end()).isEqualTo(751);
        assertThat(segments.get(1).start()).isEqualTo(631);
        assertExactSourceAndCoverage("legacy", text, segments, CODE_POINTS);
    }

    @Test
    void repeatedTextKeepsItsActualOccurrencesAndOffsets() {
        String text = "同".repeat(2_100);
        List<TextSegment> segments = new CharacterWindowChunker().split("repeated", text, CODE_POINTS);

        assertThat(segments).extracting(TextSegment::start).containsExactly(0, 780, 1560);
        assertThat(segments).extracting(TextSegment::end).containsExactly(900, 1680, 2100);
        assertThat(segments.get(0).text()).isEqualTo(segments.get(1).text());
        assertExactSourceAndCoverage("repeated", text, segments, CODE_POINTS);
    }

    @Test
    void defaultsExposeTheExperimentBudgets() {
        TokenWindowChunker token = new TokenWindowChunker();
        SentenceTokenChunker sentence = new SentenceTokenChunker();

        assertThat(new CharacterWindowChunker().id()).isEqualTo("character_900_120");
        assertThat(token.id()).isEqualTo("token_256_32");
        assertThat(token.maxTokens()).isEqualTo(256);
        assertThat(token.overlapTokens()).isEqualTo(32);
        assertThat(sentence.id()).isEqualTo("sentence_384_48");
        assertThat(sentence.maxTokens()).isEqualTo(384);
        assertThat(sentence.overlapTokens()).isEqualTo(48);
    }

    @Test
    void longUnpunctuatedTextMakesProgressAndRetainsBoundedTokenOverlap() {
        String text = "无".repeat(10_000);
        for (ChunkingStrategy strategy : List.of(new TokenWindowChunker(), new SentenceTokenChunker())) {
            List<TextSegment> segments = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    Duration.ofSeconds(3), () -> strategy.split("long", text, CODE_POINTS));
            int limit = strategy instanceof TokenWindowChunker ? 256 : 384;
            int overlap = strategy instanceof TokenWindowChunker ? 32 : 48;

            assertThat(segments).hasSizeGreaterThan(1);
            assertThat(segments).allSatisfy(segment -> assertThat(segment.tokenCount()).isLessThanOrEqualTo(limit));
            for (int index = 1; index < segments.size(); index++) {
                TextSegment previous = segments.get(index - 1);
                TextSegment current = segments.get(index);
                assertThat(previous.end() - current.start()).isEqualTo(overlap);
                assertThat(current.end()).isGreaterThan(previous.end());
            }
            assertExactSourceAndCoverage("long", text, segments, CODE_POINTS);
        }
    }

    @Test
    void sentenceChunksPreferParagraphsBeforeSplittingTheFollowingLongSentence() {
        String paragraph = "甲".repeat(20) + "。\r\n";
        String text = paragraph + "乙".repeat(100) + "。";
        List<TextSegment> segments = new SentenceTokenChunker(60, 10).split("paragraph", text, CODE_POINTS);

        assertThat(segments.get(0).text()).isEqualTo(paragraph);
        assertThat(segments.get(1).start()).isEqualTo(paragraph.length() - 2);
        assertThat(segments.get(1).text()).startsWith("\r\n");
        assertThat(segments).allSatisfy(segment -> assertThat(segment.tokenCount()).isLessThanOrEqualTo(60));
        assertExactSourceAndCoverage("paragraph", text, segments, CODE_POINTS);
    }

    @Test
    void sentenceChunksOverlapCompleteSentencesWhenTheyFit() {
        String sentence = "甲".repeat(20) + "。";
        String text = sentence.repeat(6);
        List<TextSegment> segments = new SentenceTokenChunker(50, 25).split("sentences", text, CODE_POINTS);

        assertThat(segments.get(0).end()).isEqualTo(42);
        assertThat(segments.get(1).start()).isEqualTo(21);
        assertThat(segments).allSatisfy(segment -> assertThat(segment.tokenCount()).isLessThanOrEqualTo(50));
        assertExactSourceAndCoverage("sentences", text, segments, CODE_POINTS);
    }

    @Test
    void sentenceBoundariesIncludeClosingQuotes() {
        String sentence = "甲".repeat(15) + "。”";
        String text = sentence + "乙".repeat(50);
        List<TextSegment> segments = new SentenceTokenChunker(20, 3).split("quotes", text, CODE_POINTS);

        assertThat(segments.get(0).text()).isEqualTo(sentence);
        assertExactSourceAndCoverage("quotes", text, segments, CODE_POINTS);
    }

    @Test
    void mixedEnglishChineseKeepsDecimalsAndEverySourceCharacter() {
        String text = ("English audio uses version 3.14. 中文检索保留上下文！\r\n"
                + "模型 can answer in both languages; evidence stays linked.\n").repeat(30);
        for (ChunkingStrategy strategy : List.of(new CharacterWindowChunker(),
                new TokenWindowChunker(64, 8), new SentenceTokenChunker(80, 12))) {
            List<TextSegment> segments = strategy.split("mixed", text, CODE_POINTS);
            assertExactSourceAndCoverage("mixed", text, segments, CODE_POINTS);
            if (!(strategy instanceof CharacterWindowChunker)) {
                int limit = strategy instanceof TokenWindowChunker ? 64 : 80;
                assertThat(segments).allSatisfy(segment -> assertThat(segment.tokenCount()).isLessThanOrEqualTo(limit));
            }
        }
    }

    @Test
    void emojiNeverBreakSurrogatePairsAtWindowOrOverlapBoundaries() {
        String text = "a".repeat(899) + "😀" + ("中😀English🚀。").repeat(180);
        for (ChunkingStrategy strategy : List.of(new CharacterWindowChunker(),
                new TokenWindowChunker(17, 5), new SentenceTokenChunker(23, 7))) {
            List<TextSegment> segments = strategy.split("emoji", text, CODE_POINTS);
            assertExactSourceAndCoverage("emoji", text, segments, CODE_POINTS);
            if (!(strategy instanceof CharacterWindowChunker)) {
                int limit = strategy instanceof TokenWindowChunker ? 17 : 23;
                assertThat(segments).allSatisfy(segment -> assertThat(segment.tokenCount()).isLessThanOrEqualTo(limit));
            }
        }
        assertThat(new CharacterWindowChunker().split("emoji", text, CODE_POINTS).get(0).end()).isEqualTo(899);
    }

    @Test
    void originalCrLfAndSurroundingWhitespaceArePreservedInSourceRanges() {
        String text = "  \r\n" + ("保留 original text 😀。\r\n\t").repeat(100) + "\r\n  ";
        for (ChunkingStrategy strategy : List.of(new CharacterWindowChunker(),
                new TokenWindowChunker(40, 6), new SentenceTokenChunker(50, 8))) {
            assertExactSourceAndCoverage("whitespace", text,
                    strategy.split("whitespace", text, CODE_POINTS), CODE_POINTS);
        }
    }

    @Test
    void verifiesEachChosenSentenceBoundaryEvenWhenPrefixTokenCountsAreNotMonotonic() {
        ToIntFunction<String> counter = text -> CODE_POINTS.applyAsInt(text) + (text.endsWith(".") ? 5 : 0);
        String text = "aaaa.aa.bbb.cc.dd".repeat(30);
        for (ChunkingStrategy strategy : List.of(new TokenWindowChunker(8, 2),
                new SentenceTokenChunker(8, 2))) {
            List<TextSegment> segments = strategy.split("nonmonotonic", text, counter);
            assertThat(segments).allSatisfy(segment -> assertThat(segment.tokenCount()).isLessThanOrEqualTo(8));
            assertExactSourceAndCoverage("nonmonotonic", text, segments, counter);
        }
    }

    @Test
    void zeroTokenTextBetweenFactsDoesNotPreventProgress() {
        ToIntFunction<String> counter = text -> (int) text.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
        String text = "a" + " ".repeat(1000) + "b" + "\n".repeat(1000) + "c";
        for (ChunkingStrategy strategy : List.of(new TokenWindowChunker(2, 1),
                new SentenceTokenChunker(2, 1))) {
            List<TextSegment> segments = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    Duration.ofSeconds(3), () -> strategy.split("sparse", text, counter));
            assertThat(segments).allSatisfy(segment -> assertThat(segment.tokenCount()).isLessThanOrEqualTo(2));
            assertExactSourceAndCoverage("sparse", text, segments, counter);
        }
    }

    @Test
    void rejectsImpossibleBudgetsAndInvalidCountsInsteadOfSilentlyTruncating() {
        assertThatThrownBy(() -> new TokenWindowChunker(8, 8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SentenceTokenChunker(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CharacterWindowChunker(10, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenWindowChunker(1, 0).split("impossible", "😀", ignored -> 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("code point");
        assertThatThrownBy(() -> new CharacterWindowChunker().split("invalid", "content", ignored -> -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nonnegative");
    }

    @Test
    void skipsAbsentAndBlankDocuments() {
        for (ChunkingStrategy strategy : List.of(new CharacterWindowChunker(),
                new TokenWindowChunker(), new SentenceTokenChunker())) {
            assertThat(strategy.split("empty", null, CODE_POINTS)).isEmpty();
            assertThat(strategy.split("empty", "", CODE_POINTS)).isEmpty();
            assertThat(strategy.split("empty", " \t\r\n ", CODE_POINTS)).isEmpty();
        }
    }

    private static void assertExactSourceAndCoverage(String documentId, String text,
                                                     List<TextSegment> segments,
                                                     ToIntFunction<String> counter) {
        assertThat(segments).isNotEmpty();
        boolean[] covered = new boolean[text.length()];
        int previousStart = -1;
        int coveredEnd = 0;
        for (int index = 0; index < segments.size(); index++) {
            TextSegment segment = segments.get(index);
            assertThat(segment.documentId()).isEqualTo(documentId);
            assertThat(segment.ordinal()).isEqualTo(index);
            assertThat(segment.id()).isEqualTo(documentId + ":" + index);
            assertThat(segment.start()).isGreaterThan(previousStart).isLessThanOrEqualTo(coveredEnd);
            assertThat(segment.end()).isGreaterThan(segment.start()).isLessThanOrEqualTo(text.length());
            assertThat(segment.text()).isEqualTo(text.substring(segment.start(), segment.end()));
            assertThat(segment.tokenCount()).isEqualTo(counter.applyAsInt(segment.text()));
            assertCodePointBoundary(text, segment.start());
            assertCodePointBoundary(text, segment.end());
            for (int offset = segment.start(); offset < segment.end(); offset++) {
                covered[offset] = true;
            }
            previousStart = segment.start();
            coveredEnd = Math.max(coveredEnd, segment.end());
        }
        assertThat(coveredEnd).isEqualTo(text.length());
        for (int offset = 0; offset < covered.length; offset++) {
            assertThat(covered[offset]).as("source UTF-16 offset %s is covered", offset).isTrue();
        }
    }

    private static void assertCodePointBoundary(String text, int offset) {
        if (offset > 0 && offset < text.length()) {
            assertThat(Character.isHighSurrogate(text.charAt(offset - 1))
                    && Character.isLowSurrogate(text.charAt(offset)))
                    .as("UTF-16 offset %s must not split a surrogate pair", offset).isFalse();
        }
    }
}
