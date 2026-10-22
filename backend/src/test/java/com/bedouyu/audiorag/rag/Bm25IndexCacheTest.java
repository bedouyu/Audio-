package com.bedouyu.audiorag.rag;
import com.bedouyu.audiorag.rag.index.Bm25IndexCache;
import com.bedouyu.audiorag.rag.strategy.TextSegment;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class Bm25IndexCacheTest {
    private TextSegment chunk(String id, String text) { return new TextSegment(id, 0, 0, text.length(), text, text.length()); }
    @Test void sameContentReusesRankerButChangedContentRebuilds() {
        var cache = new Bm25IndexCache(); var first = cache.acquire(7, List.of(chunk("doc", "旧版预算")));
        var same = cache.acquire(7, List.of(chunk("doc", "旧版预算")));
        assertThat(same.status()).isEqualTo("HIT"); assertThat(same.ranker()).isSameAs(first.ranker());
        var changed = cache.acquire(7, List.of(chunk("doc", "新版参数")));
        assertThat(changed.status()).isEqualTo("MISS_REBUILT"); assertThat(changed.revision()).isNotEqualTo(first.revision());
        assertThat(changed.ranker().rank("预算", 5)).isEmpty(); assertThat(cache.size()).isEqualTo(1);
    }
    @Test void projectAndDocumentSelectionDoNotShareIndexes() {
        var cache = new Bm25IndexCache(); var text = List.of(chunk("doc", "权限认证"));
        var a = cache.acquire(1, text); var b = cache.acquire(2, text);
        assertThat(a.ranker()).isNotSameAs(b.ranker()); assertThat(cache.size()).isEqualTo(2);
        var empty = cache.acquire(1, List.of()); assertThat(empty.ranker().rank("权限", 5)).isEmpty();
        assertThat(empty.revision()).isNotEqualTo(a.revision());
    }
    @Test void orderingIsCanonicalAndDeletionChangesRevision() {
        var cache = new Bm25IndexCache(); var a = chunk("a", "认证流程"); var b = chunk("b", "申请流程");
        var first = cache.acquire(1, List.of(a, b));
        assertThat(cache.acquire(1, List.of(b, a)).ranker()).isSameAs(first.ranker());
        assertThat(cache.acquire(1, List.of(a)).revision()).isNotEqualTo(first.revision());
    }
    @Test void capacityAndExplicitInvalidationAreBounded() {
        var cache = new Bm25IndexCache(1, 20);
        cache.acquire(1, List.of(chunk("doc", "认证"))); cache.acquire(2, List.of(chunk("doc", "认证")));
        assertThat(cache.size()).isEqualTo(1); assertThat(cache.retainedCharacters()).isLessThanOrEqualTo(20);
        assertThat(cache.acquire(3, List.of(chunk("doc", "长".repeat(30)))).status()).isEqualTo("BYPASS_CAPACITY");
        assertThat(cache.size()).isEqualTo(1); cache.invalidate(2); assertThat(cache.size()).isZero();
    }
}
