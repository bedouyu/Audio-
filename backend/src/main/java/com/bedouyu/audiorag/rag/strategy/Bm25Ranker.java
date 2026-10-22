package com.bedouyu.audiorag.rag.strategy;

import java.util.*;
import java.util.regex.Pattern;

/** 独立词法召回，而非给向量结果加关键词分。轻量实现，不是 Lucene 或学习型重排器。 */
public final class Bm25Ranker {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+", Pattern.UNICODE_CHARACTER_CLASS);
    private final List<TextSegment> chunks;
    private final List<Map<String, Integer>> frequencies = new ArrayList<>();
    private final Map<String, Integer> documentFrequency = new HashMap<>();
    private final List<Integer> lengths = new ArrayList<>();
    private final double averageLength;

    public Bm25Ranker(List<TextSegment> chunks) {
        this.chunks = List.copyOf(chunks);
        int total = 0;
        for (TextSegment chunk : chunks) {
            Map<String, Integer> frequency = new HashMap<>();
            for (String term : terms(chunk.text())) frequency.merge(term, 1, Integer::sum);
            frequencies.add(frequency);
            frequency.keySet().forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
            int size = frequency.values().stream().mapToInt(Integer::intValue).sum();
            lengths.add(size); total += size;
        }
        averageLength = chunks.isEmpty() ? 1 : Math.max(1, (double) total / chunks.size());
    }

    public List<RankedChunk> rank(String query, int limit) {
        Set<String> queryTerms = new HashSet<>(terms(query));
        List<RankedChunk> scored = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            double score = 0;
            for (String term : queryTerms) {
                int tf = frequencies.get(i).getOrDefault(term, 0);
                if (tf == 0) continue;
                int df = documentFrequency.get(term);
                double idf = Math.log(1 + (chunks.size() - df + .5) / (df + .5));
                score += idf * tf * 2.2 / (tf + 1.2 * (.25 + .75 * lengths.get(i) / averageLength));
            }
            if (score > 0) scored.add(new RankedChunk(chunks.get(i), score, 0));
        }
        return HybridRanking.sorted(scored, limit);
    }

    /** 中文单字+二元词，英文/数字连续词；没有语言模型推理或领域词典。 */
    static List<String> terms(String text) {
        List<String> terms = new ArrayList<>();
        var matches = WORD.matcher(text == null ? "" : text.toLowerCase(Locale.ROOT));
        while (matches.find()) {
            StringBuilder latin = new StringBuilder();
            String previousHan = null;
            for (int cp : matches.group().codePoints().toArray()) {
                if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) {
                    if (!latin.isEmpty()) { terms.add(latin.toString()); latin.setLength(0); }
                    String han = new String(Character.toChars(cp));
                    terms.add(han);
                    if (previousHan != null) terms.add(previousHan + han);
                    previousHan = han;
                } else { latin.appendCodePoint(cp); previousHan = null; }
            }
            if (!latin.isEmpty()) terms.add(latin.toString());
        }
        return terms;
    }
}
