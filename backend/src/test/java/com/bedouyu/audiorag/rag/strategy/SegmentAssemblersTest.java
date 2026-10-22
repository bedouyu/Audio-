package com.bedouyu.audiorag.rag.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

class SegmentAssemblersTest {
    // Character counts make these algorithm fixtures exact; they are not retrieval-quality measurements.
    private static final ToIntFunction<String> COUNT = String::length;
    private static final List<SegmentAssembler> METHODS = List.of(new TopKSegmentAssembler(),
            new NeighborWindowAssembler(), new RseSegmentAssembler());

    @Test
    void rseBridgesAnUnretrievedGapWhenCombinedValueIsHigher() {
        String text = "AAAABBBBCCCC";
        List<TextSegment> chunks = chunks("d", text, 4);
        var result = new RseSegmentAssembler().assemble(Map.of("d", text), chunks,
                List.of(ranked(chunks.get(0), 0), ranked(chunks.get(2), 1)), 100, COUNT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).text()).isEqualTo(text);
        assertThat(result.get(0).chunkIds()).containsExactly("d:0", "d:1", "d:2");
        assertThat(result.get(0).utility()).isGreaterThan(0.8);
    }

    @Test
    void rseLeavesALargeNoiseGapOutEvenWhenItsLengthWouldBeAllowed() {
        String text = "AAAABBBBCCCCDDDDEEEEFFFFGGGG";
        List<TextSegment> chunks = chunks("d", text, 4);
        var result = new RseSegmentAssembler(8, 10, 0.2, 0.1).assemble(Map.of("d", text), chunks,
                List.of(ranked(chunks.get(0), 0), ranked(chunks.get(6), 1)), 100, COUNT);

        assertThat(result).extracting(EvidenceSegment::text).containsExactly("AAAA", "GGGG");
        assertThat(result).allMatch(segment -> segment.chunkIds().size() == 1);
    }

    @Test
    void everyMethodRestoresOverlappingTextOnceFromTheOriginalDocument() {
        String text = "abcdefghij";
        var chunks = List.of(chunk("d", 0, text, 0, 6), chunk("d", 1, text, 4, 10));
        var candidates = List.of(ranked(chunks.get(0), 0), ranked(chunks.get(1), 1));

        for (SegmentAssembler method : METHODS) {
            var result = method.assemble(Map.of("d", text), chunks, candidates, 100, COUNT);
            assertThat(result).as(method.id()).hasSize(1);
            assertThat(result.get(0).text()).as(method.id()).isEqualTo(text);
            assertThat(result.get(0).contextTokens()).isEqualTo(COUNT.applyAsInt(ContextText.render("d", text)));
            assertThat(result.get(0).chunkIds()).containsExactly("d:0", "d:1");
        }
    }

    @Test
    void sameHardBudgetExposesDifferentAssemblyPolicies() {
        String text = "AAAABBBBCCCCDDDDEEEE";
        List<TextSegment> chunks = chunks("d", text, 4);
        var candidates = List.of(ranked(chunks.get(0), 0), ranked(chunks.get(4), 1));
        int budget = COUNT.applyAsInt(ContextText.render("d", text));
        var topK = METHODS.get(0).assemble(Map.of("d", text), chunks, candidates, budget, COUNT);
        var windows = METHODS.get(1).assemble(Map.of("d", text), chunks, candidates, budget, COUNT);
        var rse = METHODS.get(2).assemble(Map.of("d", text), chunks, candidates, budget, COUNT);

        assertThat(topK).extracting(EvidenceSegment::text).containsExactly("AAAA", "EEEE");
        assertThat(windows).extracting(EvidenceSegment::text).containsExactly("AAAABBBB");
        assertThat(rse).extracting(EvidenceSegment::text).containsExactly(text);
        for (var result : List.of(topK, windows, rse)) {
            assertThat(result.stream().mapToInt(EvidenceSegment::contextTokens).sum()).isLessThanOrEqualTo(budget);
        }
    }

    @Test
    void headerAndSeparatorsCountAgainstBudgetAndBodiesAreNeverCutToFit() {
        String text = "abcdef";
        List<TextSegment> chunks = chunks("long-document-id", text, 6);
        var candidates = List.of(ranked(chunks.get(0), 0));
        Map<String, String> documents = Map.of("long-document-id", text);
        int exactBudget = COUNT.applyAsInt(ContextText.render("long-document-id", text));
        for (SegmentAssembler method : METHODS) {
            assertThat(method.assemble(documents, chunks, candidates, text.length(), COUNT)).as(method.id()).isEmpty();
            assertThat(method.assemble(documents, chunks, candidates, exactBudget - 1, COUNT)).as(method.id()).isEmpty();
            var result = method.assemble(documents, chunks, candidates, exactBudget, COUNT);
            assertThat(result).as(method.id()).hasSize(1);
            assertThat(result.get(0).text()).isEqualTo(text);
            assertThat(result.get(0).contextTokens()).isEqualTo(exactBudget);
            assertThat(method.assemble(documents, chunks, candidates, 0, COUNT)).isEmpty();
        }
    }

    @Test
    void windowsMergeConnectedRangesWithoutJoiningDifferentDocuments() {
        Map<String, String> documents = Map.of("a", "AAAABBBBCCCC", "b", "DDDDEEEE");
        List<TextSegment> all = new ArrayList<>(chunks("a", documents.get("a"), 4));
        all.addAll(chunks("b", documents.get("b"), 4));
        var result = new NeighborWindowAssembler().assemble(documents, all,
                List.of(ranked(all.get(0), 0), ranked(all.get(2), 1), ranked(all.get(3), 2)), 100, COUNT);

        assertThat(result).extracting(EvidenceSegment::documentId).containsExactly("a", "b");
        assertThat(result).extracting(EvidenceSegment::text).containsExactly("AAAABBBBCCCC", "DDDDEEEE");
        assertThat(result.get(0).chunkIds()).containsExactly("a:0", "a:1", "a:2");
    }

    @Test
    void rseDoesNotCrossDocumentBoundariesOrSelectOverlappingSourceIntervals() {
        String text = "abcdefghij";
        var chunks = List.of(chunk("a", 0, text, 0, 6), chunk("a", 1, text, 4, 10),
                chunk("b", 0, "uvwxyz", 0, 6));
        var result = new RseSegmentAssembler(1, 10, 0.2, 0.1).assemble(
                Map.of("a", text, "b", "uvwxyz"), chunks,
                List.of(ranked(chunks.get(0), 0), ranked(chunks.get(1), 1), ranked(chunks.get(2), 2)), 100, COUNT);

        assertThat(result).extracting(EvidenceSegment::documentId).containsExactly("a", "b");
        assertThat(result).extracting(EvidenceSegment::text).containsExactly("abcdef", "uvwxyz");
    }

    @Test
    void tiesAndInputOrderAreDeterministicAndRseIgnoresRawScoreScales() {
        Map<String, String> documents = new LinkedHashMap<>();
        documents.put("b", "BBBB");
        documents.put("a", "AAAA");
        var all = List.of(chunk("b", 0, "BBBB", 0, 4), chunk("a", 0, "AAAA", 0, 4));
        var candidates = List.of(new RankedChunk(all.get(0), 1_000_000, 0), new RankedChunk(all.get(1), -1_000_000, 0));
        var reversedChunks = new ArrayList<>(all);
        var reversedCandidates = new ArrayList<>(candidates);
        Collections.reverse(reversedChunks);
        Collections.reverse(reversedCandidates);
        for (SegmentAssembler method : METHODS) {
            var first = method.assemble(documents, all, candidates, 100, COUNT);
            var second = method.assemble(documents, reversedChunks, reversedCandidates, 100, COUNT);
            assertThat(first).as(method.id()).isEqualTo(second);
            assertThat(first).extracting(EvidenceSegment::documentId).containsExactly("a", "b");
        }
        var changedScores = List.of(new RankedChunk(all.get(0), -50, 0), new RankedChunk(all.get(1), 50, 0));
        var rse = new RseSegmentAssembler();
        assertThat(rse.assemble(documents, all, candidates, 100, COUNT))
                .isEqualTo(rse.assemble(documents, all, changedScores, 100, COUNT));
    }

    @Test
    void invalidConfigurationAndStaleChunksFailExplicitly() {
        assertThatThrownBy(() -> new RseSegmentAssembler(0, 10, 0.2, 0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RseSegmentAssembler(6, Double.NaN, 0.2, 0.1)).isInstanceOf(IllegalArgumentException.class);
        var stale = new TextSegment("d", 0, 0, 4, "nope", 4);
        for (SegmentAssembler method : METHODS) {
            assertThatThrownBy(() -> method.assemble(Map.of("d", "text"), List.of(stale), List.of(), 100, COUNT))
                    .as(method.id()).isInstanceOf(IllegalArgumentException.class);
            assertThat(method.assemble(Map.of(), List.of(), List.of(), 100, COUNT)).isEmpty();
        }
    }

    private static RankedChunk ranked(TextSegment chunk, int rank) { return new RankedChunk(chunk, 0.95, rank); }

    private static TextSegment chunk(String documentId, int ordinal, String text, int start, int end) {
        return new TextSegment(documentId, ordinal, start, end, text.substring(start, end), end - start);
    }

    private static List<TextSegment> chunks(String documentId, String text, int size) {
        List<TextSegment> chunks = new ArrayList<>();
        for (int start = 0; start < text.length(); start += size) {
            chunks.add(chunk(documentId, chunks.size(), text, start, Math.min(text.length(), start + size)));
        }
        return List.copyOf(chunks);
    }
}
