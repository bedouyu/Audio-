package com.bedouyu.audiorag.rag.strategy;

import java.util.*;

/** RRF 按排名融合，绝不能直接相加 cosine 与 BM25 分数。 */
public final class HybridRanking {
    private HybridRanking() { }
    public static List<RankedChunk> fuse(List<RankedChunk> dense, List<RankedChunk> lexical, int limit) {
        Map<String, RankedChunk> identities = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();
        for (List<RankedChunk> route : List.of(dense, lexical)) {
            Set<String> seen = new HashSet<>();
            for (RankedChunk hit : route) {
                String id = hit.chunk().id();
                RankedChunk old = identities.putIfAbsent(id, hit);
                if (old != null && !old.chunk().equals(hit.chunk()))
                    throw new IllegalArgumentException("同一片段 ID 对应不同原文");
                if (seen.add(id)) scores.merge(id, 1.0 / (60 + hit.rank() + 1), Double::sum);
            }
        }
        return sorted(scores.entrySet().stream().map(entry ->
                new RankedChunk(identities.get(entry.getKey()).chunk(), entry.getValue(), 0)).toList(), limit);
    }
    public static List<RankedChunk> rankDense(List<TextSegment> chunks, List<float[]> vectors,
                                             float[] query, int limit) {
        if (chunks.size() != vectors.size()) throw new IllegalArgumentException("向量数量不符");
        List<RankedChunk> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = vectors.get(i);
            if (vector.length != query.length) throw new IllegalArgumentException("向量维度不符");
            double dot = 0, a = 0, b = 0;
            for (int j = 0; j < query.length; j++) {
                if (!Float.isFinite(vector[j]) || !Float.isFinite(query[j]))
                    throw new IllegalArgumentException("向量存在非有限数值");
                dot += (double) vector[j] * query[j]; a += (double) vector[j] * vector[j];
                b += (double) query[j] * query[j];
            }
            if (a > 0 && b > 0) result.add(new RankedChunk(chunks.get(i), dot / Math.sqrt(a * b), 0));
        }
        return sorted(result, limit);
    }
    static List<RankedChunk> sorted(List<RankedChunk> candidates, int limit) {
        if (limit < 0) throw new IllegalArgumentException("limit 必须非负");
        List<RankedChunk> ordered = candidates.stream().sorted(Comparator
                .comparingDouble(RankedChunk::score).reversed().thenComparing(hit -> hit.chunk().id()))
                .limit(limit).toList();
        List<RankedChunk> result = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++)
            result.add(new RankedChunk(ordered.get(i).chunk(), ordered.get(i).score(), i));
        return List.copyOf(result);
    }
}
