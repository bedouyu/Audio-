package com.bedouyu.audiorag.rag.strategy;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RankingTest {
    private TextSegment chunk(String id, String text) { return new TextSegment(id, 0, 0, text.length(), text, text.length()); }
    @Test void lexicalRecallIsIndependentAndFindsExactCodesAndChineseTerms() {
        var chunks = List.of(chunk("a", "数据库错误 E_CONN_517，需要更换端口。"),
                chunk("b", "语音合成是文字转音频，语音识别是音频转文字。"), chunk("c", "其他无关内容"));
        assertThat(new Bm25Ranker(chunks).rank("E_CONN_517", 10)).extracting(h -> h.chunk().documentId()).containsExactly("a");
        assertThat(new Bm25Ranker(chunks).rank("语音合成", 1).get(0).chunk().documentId()).isEqualTo("b");
        assertThat(new Bm25Ranker(chunks).rank("completely_absent", 5)).isEmpty();
    }
    @Test void rrfUsesRanksNotIncomparableScoreScales() {
        var a = chunk("a", "first"); var b = chunk("b", "second");
        var result = HybridRanking.fuse(List.of(new RankedChunk(a, .9, 0), new RankedChunk(b, .8, 1)),
                List.of(new RankedChunk(b, 10000, 0)), 2);
        assertThat(result.get(0).chunk()).isEqualTo(b);
        assertThat(result.get(0).score()).isLessThan(.04);
        assertThat(result).extracting(RankedChunk::rank).containsExactly(0, 1);
    }
    @Test void cosineNormalizesVectorsAndRejectsInvalidDimensions() {
        var a = chunk("a", "first"); var b = chunk("b", "second");
        assertThat(HybridRanking.rankDense(List.of(a, b), List.of(new float[]{5, 0}, new float[]{1, 1}),
                new float[]{2, 0}, 2).get(0).score()).isEqualTo(1.0);
        assertThatThrownBy(() -> HybridRanking.rankDense(List.of(a), List.of(new float[]{1}), new float[]{1, 2}, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rrfRejectsConflictingIdentityAndHandlesMissingRoute() {
        var a = chunk("a", "first");
        assertThat(HybridRanking.fuse(List.of(), List.of(new RankedChunk(a, 1, 0)), 1)).hasSize(1);
        assertThatThrownBy(() -> HybridRanking.fuse(List.of(new RankedChunk(a, 1, 0)),
                List.of(new RankedChunk(chunk("a", "other"), 2, 0)), 1)).hasMessageContaining("不同原文");
    }
}
