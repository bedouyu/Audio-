package com.bedouyu.audiorag.rag.strategy;
import java.util.List;
import java.util.function.ToIntFunction;
public interface ChunkingStrategy {
    String id();
    List<TextSegment> split(String documentId, String text, ToIntFunction<String> contentTokenCount);
}
