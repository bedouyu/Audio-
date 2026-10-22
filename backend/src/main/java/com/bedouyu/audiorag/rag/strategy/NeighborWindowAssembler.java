package com.bedouyu.audiorag.rag.strategy;

import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/** A fixed +/- one complete chunk window; deliberately distinct from RSE. */
public final class NeighborWindowAssembler implements SegmentAssembler {
    @Override
    public String id() { return "neighbor_window_1"; }

    @Override
    public List<EvidenceSegment> assemble(Map<String, String> documents, List<TextSegment> completeChunks,
            List<RankedChunk> candidates, int contextTokenBudget, ToIntFunction<String> contentTokenCount) {
        var catalog = SegmentAssemblySupport.catalog(documents, completeChunks, contextTokenBudget, contentTokenCount);
        List<EvidenceSegment> selected = List.of();
        for (RankedChunk candidate : SegmentAssemblySupport.ranked(catalog, candidates)) {
            TextSegment anchor = candidate.chunk();
            List<TextSegment> documentChunks = catalog.byDocument().get(anchor.documentId());
            int start = Math.max(0, anchor.ordinal() - 1);
            int end = Math.min(documentChunks.size(), anchor.ordinal() + 2);
            EvidenceSegment proposal = SegmentAssemblySupport.segment(catalog, documentChunks.subList(start, end),
                    1.0 / (candidate.rank() + 1.0), contentTokenCount);
            List<EvidenceSegment> next = SegmentAssemblySupport.merge(selected, proposal, catalog,
                    contentTokenCount, true);
            if (SegmentAssemblySupport.tokens(next) <= contextTokenBudget) {
                selected = next;
            }
        }
        return List.copyOf(selected);
    }
}
