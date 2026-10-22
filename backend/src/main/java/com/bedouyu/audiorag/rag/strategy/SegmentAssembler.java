package com.bedouyu.audiorag.rag.strategy;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
public interface SegmentAssembler {
    String id();
    List<EvidenceSegment> assemble(Map<String, String> documents, List<TextSegment> completeChunks,
            List<RankedChunk> candidates, int contextTokenBudget, ToIntFunction<String> contentTokenCount);
}
