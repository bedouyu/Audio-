package com.bedouyu.audiorag.rag.strategy;
/** UTF-16 原文区间 [start,end)，不会把重叠文字重复拼接。tokenCount 不包含 CLS/SEP。 */
public record TextSegment(String documentId, int ordinal, int start, int end, String text, int tokenCount) {
    public TextSegment {
        if (start < 0 || end < start || end - start != text.length() || tokenCount < 0)
            throw new IllegalArgumentException("片段区间或 token 数无效");
    }
    public String id() { return documentId + ":" + ordinal; }
}
