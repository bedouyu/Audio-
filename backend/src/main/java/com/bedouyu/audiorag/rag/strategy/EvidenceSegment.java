package com.bedouyu.audiorag.rag.strategy;
import java.util.List;
public record EvidenceSegment(String documentId, int start, int end, String text,
                              int contextTokens, double utility, List<String> chunkIds) {
    public EvidenceSegment { chunkIds = List.copyOf(chunkIds); }
}
