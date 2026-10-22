package com.bedouyu.audiorag.rag.strategy;
/** rank 从 0 开始；score 保留召回器原始分数，不假装是相关概率。 */
public record RankedChunk(TextSegment chunk, double score, int rank) { }
