package com.bedouyu.audiorag.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryNormalizerTest {
    @Test void productionDirectivesAreRemovedButTheTopicRemains() {
        assertThat(QueryNormalizer.forRetrieval("整理今天的时政，做成 10 分钟简报")).contains("时政").doesNotContain("分钟").doesNotContain("做成");
        assertThat(QueryNormalizer.forRetrieval("请帮我用中文讲一段量子计算的故事")).contains("量子计算").doesNotContain("中文");
        assertThat(QueryNormalizer.forRetrieval("把概率论讲成 5 分钟音频")).contains("概率论");
    }

    @Test void durationAndLanguageWordsDoNotSurvive() {
        String normalized = QueryNormalizer.forRetrieval("tell me a 5 minute bedtime story in English");

        assertThat(normalized).doesNotContain("5").doesNotContain("minute").doesNotContain("English");
        assertThat(normalized).contains("bedtime story");
    }

    @Test void stoppingAtTheTopicAndFallingBackWhenNothingIsLeft() {
        // 剥掉“讲个”后剩下的“故事”仍是可用检索词。
        assertThat(QueryNormalizer.forRetrieval("讲个故事")).isEqualTo("故事");
        // 整句都是指令时不能返回空串，必须回退原文。
        assertThat(QueryNormalizer.forRetrieval("播报")).isEqualTo("播报");
        assertThat(QueryNormalizer.forRetrieval("")).isEmpty();
        assertThat(QueryNormalizer.forRetrieval(null)).isEmpty();
    }

    @Test void plainQueriesAreLeftAlone() {
        assertThat(QueryNormalizer.forRetrieval("量子计算")).isEqualTo("量子计算");
        assertThat(QueryNormalizer.forRetrieval("BM25 与向量检索的区别")).isEqualTo("BM25 与向量检索的区别");
    }

    @Test void variantsOfferBothFormsOnlyWhenTheyDiffer() {
        assertThat(QueryNormalizer.variants("量子计算")).containsExactly("量子计算");
        assertThat(QueryNormalizer.variants("整理今天的时政，做成 10 分钟简报"))
                .hasSize(2).allMatch(item -> !item.isBlank());
    }
}
