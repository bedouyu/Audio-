package com.bedouyu.audiorag.rag.strategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/** Ranked chunks, packed greedily without truncating source text. */
public final class TopKSegmentAssembler implements SegmentAssembler {
    @Override
    public String id() { return "top_k"; }

    @Override
    public List<EvidenceSegment> assemble(Map<String, String> documents, List<TextSegment> completeChunks,
            List<RankedChunk> candidates, int contextTokenBudget, ToIntFunction<String> contentTokenCount) {
        var catalog = SegmentAssemblySupport.catalog(documents, completeChunks, contextTokenBudget, contentTokenCount);
        List<EvidenceSegment> selected = List.of();
        for (RankedChunk candidate : SegmentAssemblySupport.ranked(catalog, candidates)) {
            TextSegment chunk = candidate.chunk();
            EvidenceSegment proposal = SegmentAssemblySupport.segment(catalog, List.of(chunk),
                    1.0 / (candidate.rank() + 1.0), contentTokenCount);
            List<EvidenceSegment> next = SegmentAssemblySupport.merge(selected, proposal, catalog,
                    contentTokenCount, false);
            if (SegmentAssemblySupport.tokens(next) <= contextTokenBudget) {
                selected = next;
            }
        }
        return List.copyOf(selected);
    }
}

/** Shared validation and original-source interval assembly for production and experiments. */
final class SegmentAssemblySupport {
    private SegmentAssemblySupport() { }

    record Catalog(Map<String, String> documents, Map<String, List<TextSegment>> byDocument,
                   Map<String, TextSegment> byId) { }

    static Catalog catalog(Map<String, String> documents, List<TextSegment> chunks, int budget,
            ToIntFunction<String> tokenCount) {
        Objects.requireNonNull(documents, "documents");
        Objects.requireNonNull(chunks, "completeChunks");
        Objects.requireNonNull(tokenCount, "contentTokenCount");
        if (budget < 0) throw new IllegalArgumentException("Context token budget must be non-negative");
        Map<String, List<TextSegment>> byDocument = new TreeMap<>();
        Map<String, TextSegment> byId = new HashMap<>();
        for (TextSegment chunk : chunks) {
            Objects.requireNonNull(chunk, "chunk");
            String document = documents.get(chunk.documentId());
            if (document == null || chunk.ordinal() < 0 || chunk.start() >= chunk.end()
                    || chunk.end() > document.length()
                    || !document.substring(chunk.start(), chunk.end()).equals(chunk.text())) {
                throw new IllegalArgumentException("Chunk does not match its original document: " + chunk.id());
            }
            if (byId.putIfAbsent(chunk.id(), chunk) != null) {
                throw new IllegalArgumentException("Duplicate chunk id: " + chunk.id());
            }
            byDocument.computeIfAbsent(chunk.documentId(), ignored -> new ArrayList<>()).add(chunk);
        }
        byDocument.replaceAll((id, documentChunks) -> {
            documentChunks.sort(Comparator.comparingInt(TextSegment::ordinal));
            int previousStart = -1;
            int previousEnd = -1;
            for (int index = 0; index < documentChunks.size(); index++) {
                TextSegment chunk = documentChunks.get(index);
                if (chunk.ordinal() != index || chunk.start() < previousStart || chunk.end() < previousEnd) {
                    throw new IllegalArgumentException("Complete chunks must have contiguous ordinals and ordered offsets: " + id);
                }
                previousStart = chunk.start();
                previousEnd = chunk.end();
            }
            return List.copyOf(documentChunks);
        });
        return new Catalog(documents, byDocument, byId);
    }

    static List<RankedChunk> ranked(Catalog catalog, List<RankedChunk> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        Map<String, RankedChunk> unique = new HashMap<>();
        for (RankedChunk candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            TextSegment canonical = catalog.byId().get(candidate.chunk().id());
            if (candidate.rank() < 0 || !candidate.chunk().equals(canonical)) {
                throw new IllegalArgumentException("Candidate is not a ranked member of the complete chunks");
            }
            unique.merge(canonical.id(), candidate, (left, right) -> left.rank() <= right.rank() ? left : right);
        }
        return unique.values().stream().sorted(Comparator.comparingInt(RankedChunk::rank)
                .thenComparing(candidate -> candidate.chunk().documentId())
                .thenComparingInt(candidate -> candidate.chunk().ordinal())).toList();
    }

    static EvidenceSegment segment(Catalog catalog, List<TextSegment> chunks, double utility,
            ToIntFunction<String> tokenCount) {
        TextSegment first = chunks.get(0);
        TextSegment last = chunks.get(chunks.size() - 1);
        return interval(catalog, first.documentId(), first.start(), last.end(), utility,
                chunks.stream().map(TextSegment::id).toList(), tokenCount);
    }

    private static EvidenceSegment interval(Catalog catalog, String documentId, int start, int end,
            double utility, List<String> ids, ToIntFunction<String> tokenCount) {
        String text = catalog.documents().get(documentId).substring(start, end);
        int tokens = tokenCount.applyAsInt(ContextText.render(documentId, text));
        if (tokens < 0) throw new IllegalArgumentException("Token counter returned a negative count");
        return new EvidenceSegment(documentId, start, end, text, tokens, utility, ids);
    }

    static long tokens(List<EvidenceSegment> segments) {
        return segments.stream().mapToLong(EvidenceSegment::contextTokens).sum();
    }

    static boolean overlaps(EvidenceSegment left, EvidenceSegment right) {
        return left.documentId().equals(right.documentId()) && left.start() < right.end() && right.start() < left.end();
    }

    /** Merge connected intervals from one document, recovering text once from the original source. */
    static List<EvidenceSegment> merge(List<EvidenceSegment> selected, EvidenceSegment proposal, Catalog catalog,
            ToIntFunction<String> tokenCount, boolean joinTouching) {
        int start = proposal.start();
        int end = proposal.end();
        double utility = proposal.utility();
        Set<Integer> absorbed = new HashSet<>();
        Set<String> ids = new LinkedHashSet<>(proposal.chunkIds());
        boolean changed;
        do {
            changed = false;
            for (int index = 0; index < selected.size(); index++) {
                EvidenceSegment existing = selected.get(index);
                boolean connected = joinTouching ? start <= existing.end() && existing.start() <= end
                        : start < existing.end() && existing.start() < end;
                if (!absorbed.contains(index) && proposal.documentId().equals(existing.documentId()) && connected) {
                    absorbed.add(index);
                    start = Math.min(start, existing.start());
                    end = Math.max(end, existing.end());
                    utility += existing.utility();
                    ids.addAll(existing.chunkIds());
                    changed = true;
                }
            }
        } while (changed);
        List<String> orderedIds = ids.stream().sorted(Comparator.comparingInt(id -> catalog.byId().get(id).ordinal())).toList();
        EvidenceSegment merged = interval(catalog, proposal.documentId(), start, end, utility, orderedIds, tokenCount);
        List<EvidenceSegment> result = new ArrayList<>();
        int insertion = absorbed.stream().mapToInt(Integer::intValue).min().orElse(selected.size());
        for (int index = 0; index < selected.size(); index++) {
            if (index == insertion) result.add(merged);
            if (!absorbed.contains(index)) result.add(selected.get(index));
        }
        if (insertion == selected.size()) result.add(merged);
        return List.copyOf(result);
    }
}
