package com.bedouyu.audiorag.rag.index;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.rag.strategy.Bm25Ranker;
import com.bedouyu.audiorag.rag.strategy.TextSegment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;

/**
 * 缓存词法索引而非模型答案。请求仍从事实库取得片段并计算内容指纹，
 * 新增/删除/改文、不同项目及不同文档选择不能复用旧索引。
 * 这是有界本机缓存，不是持久倒排服务或语义答案缓存。
 */
@Component
public class Bm25IndexCache {
    private record Key(long project, String revision) { }
    private record Entry(Bm25Ranker ranker, long chars) { }
    public record View(Bm25Ranker ranker, String revision, String status) { }
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final int maximumEntries;
    private final long maximumCharacters;
    private long retainedCharacters;
    public Bm25IndexCache() { this(16, 2_000_000); }
    @Autowired
    public Bm25IndexCache(@Value("${audiorag.rag.bm25-cache-entries:16}") int maximumEntries,
            @Value("${audiorag.rag.bm25-cache-characters:2000000}") long maximumCharacters) {
        if (maximumEntries < 1 || maximumCharacters < 1) throw new IllegalArgumentException("BM25缓存容量必须为正");
        this.maximumEntries = maximumEntries; this.maximumCharacters = maximumCharacters;
    }
    public View acquire(long project, List<TextSegment> chunks) {
        if (project < 1) throw new IllegalArgumentException("项目编号无效");
        var canonical = chunks.stream().sorted(Comparator.comparing(TextSegment::documentId)
                .thenComparingInt(TextSegment::ordinal)).toList();
        StringBuilder fingerprint = new StringBuilder("bm25-char-bigram-v1\n");
        long weight = 1;
        for (var chunk : canonical) {
            fingerprint.append(chunk.documentId()).append(':').append(chunk.ordinal()).append(':')
                    .append(chunk.start()).append(':').append(chunk.end()).append(':')
                    .append(chunk.tokenCount()).append(':').append(Hashing.sha256(chunk.text())).append('\n');
            weight += chunk.text().length();
        }
        String revision = Hashing.sha256(fingerprint.toString()); Key key = new Key(project, revision);
        synchronized (this) {
            Entry cached = entries.get(key);
            if (cached != null) return new View(cached.ranker(), revision, "HIT");
            var ranker = new Bm25Ranker(canonical);
            if (weight > maximumCharacters) return new View(ranker, revision, "BYPASS_CAPACITY");
            invalidate(project);
            while (!entries.isEmpty() && (entries.size() >= maximumEntries || retainedCharacters + weight > maximumCharacters)) {
                var eldest = entries.entrySet().iterator(); var removed = eldest.next();
                retainedCharacters -= removed.getValue().chars(); eldest.remove();
            }
            entries.put(key, new Entry(ranker, weight)); retainedCharacters += weight;
            return new View(ranker, revision, "MISS_REBUILT");
        }
    }
    public synchronized void invalidate(long project) {
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            if (item.getKey().project() == project) { retainedCharacters -= item.getValue().chars(); iterator.remove(); }
        }
    }
    public synchronized int size() { return entries.size(); }
    public synchronized long retainedCharacters() { return retainedCharacters; }
}
