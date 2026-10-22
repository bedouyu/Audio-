package com.bedouyu.audiorag.rag.strategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Local adaptation of dsRAG's greedy contiguous-segment selection.
 * Retrieved chunk value is exp(-rank / decay) - penalty; unseen chunk value is -penalty.
 * Raw retrieval scores are intentionally ignored: cosine, BM25 and RRF are not calibrated probabilities.
 * Unlike upstream's chunk-count budget, this adapter caps rendered context tokens and uses one ranking.
 * Parameters must be fixed in advance or selected on a development set, never on evaluation labels.
 */
public final class RseSegmentAssembler implements SegmentAssembler {
    private final int maxSegmentChunks;
    private final double decay;
    private final double penalty;
    private final double minimumValue;

    public RseSegmentAssembler() { this(6, 10.0, 0.2, 0.1); }

    public RseSegmentAssembler(int maxSegmentChunks, double decay, double penalty, double minimumValue) {
        if (maxSegmentChunks < 1 || !Double.isFinite(decay) || decay <= 0
                || !Double.isFinite(penalty) || penalty < 0 || penalty > 1
                || !Double.isFinite(minimumValue) || minimumValue < 0) {
            throw new IllegalArgumentException("Invalid RSE parameters");
        }
        this.maxSegmentChunks = maxSegmentChunks;
        this.decay = decay;
        this.penalty = penalty;
        this.minimumValue = minimumValue;
    }

    @Override
    public String id() { return "rse_rank_proxy"; }

    @Override
    public List<EvidenceSegment> assemble(Map<String, String> documents, List<TextSegment> completeChunks,
            List<RankedChunk> candidates, int contextTokenBudget, ToIntFunction<String> contentTokenCount) {
        var catalog = SegmentAssemblySupport.catalog(documents, completeChunks, contextTokenBudget, contentTokenCount);
        Map<String, Double> retrievedValues = new HashMap<>();
        for (RankedChunk candidate : SegmentAssemblySupport.ranked(catalog, candidates)) {
            retrievedValues.put(candidate.chunk().id(), Math.exp(-candidate.rank() / decay) - penalty);
        }
        List<EvidenceSegment> selected = new ArrayList<>();
        long usedTokens = 0;
        while (usedTokens < contextTokenBudget) {
            EvidenceSegment best = null;
            for (List<TextSegment> documentChunks : catalog.byDocument().values()) {
                for (int start = 0; start < documentChunks.size(); start++) {
                    if (value(documentChunks.get(start), retrievedValues) < 0) continue;
                    double utility = 0;
                    int endLimit = start + Math.min(maxSegmentChunks, documentChunks.size() - start);
                    for (int end = start + 1; end <= endLimit; end++) {
                        double lastValue = value(documentChunks.get(end - 1), retrievedValues);
                        utility += lastValue;
                        if (lastValue < 0 || utility < minimumValue) continue;
                        EvidenceSegment proposal = SegmentAssemblySupport.segment(catalog,
                                documentChunks.subList(start, end), utility, contentTokenCount);
                        if (usedTokens + proposal.contextTokens() > contextTokenBudget
                                || selected.stream().anyMatch(existing -> SegmentAssemblySupport.overlaps(existing, proposal))) {
                            continue;
                        }
                        if (better(proposal, best)) best = proposal;
                    }
                }
            }
            if (best == null) break;
            selected.add(best);
            usedTokens += best.contextTokens();
        }
        return List.copyOf(selected);
    }

    private double value(TextSegment chunk, Map<String, Double> retrievedValues) {
        return retrievedValues.getOrDefault(chunk.id(), -penalty);
    }

    private static boolean better(EvidenceSegment candidate, EvidenceSegment best) {
        if (best == null) return true;
        int utilityOrder = Double.compare(candidate.utility(), best.utility());
        if (utilityOrder != 0) return utilityOrder > 0;
        int documentOrder = candidate.documentId().compareTo(best.documentId());
        if (documentOrder != 0) return documentOrder < 0;
        if (candidate.start() != best.start()) return candidate.start() < best.start();
        return candidate.end() < best.end();
    }
}
