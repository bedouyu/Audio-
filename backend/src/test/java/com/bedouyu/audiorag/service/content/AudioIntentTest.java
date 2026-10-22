package com.bedouyu.audiorag.service.content;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AudioIntentTest {
    private final LocalDate today = LocalDate.of(2026, 10, 20);

    @Test void creativeEnglishKeepsFullPreferencesAndDoesNotClaimNewsOrAStudyPlan() {
        String goal = "写一个十分钟的原创睡前故事，用英语，温柔一点，不要课程开场";
        var intent = AudioIntent.resolve(goal, 5, today);
        assertThat(intent.instruction()).isEqualTo(goal);
        assertThat(intent.language()).isEqualTo("en");
        assertThat(intent.durationMinutes()).isEqualTo(10);
        assertThat(intent.creative()).isTrue();
        assertThat(intent.freshEvidenceRequired()).isFalse();
        assertThat(intent.exactSourceRequested()).isFalse();
        assertThat(intent.nextLearningPoint()).isFalse();
    }

    @Test void currentAffairsHasAnExplicitBeijingDateRangeAndRealTimeEvidenceRequirement() {
        var todayNews = AudioIntent.resolve("今天的国际新闻，5分钟，不要评论", 10, today);
        assertThat(todayNews.freshEvidenceRequired()).isTrue();
        assertThat(todayNews.fromDate()).isEqualTo(today).isEqualTo(todayNews.throughDate());
        var previous = AudioIntent.resolve("梳理 2026-10-17 至 2026-10-19 的时政", 5, today);
        assertThat(previous.fromDate()).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(previous.throughDate()).isEqualTo(LocalDate.of(2026, 10, 19));
        assertThat(previous.searchQuery()).contains("after:2026-10-16", "before:2026-10-20");
    }

    @Test void exactReadingIsDistinctFromWritingAnOriginalStory() {
        assertThat(AudioIntent.resolve("逐字朗读我上传的小说第一章", 5, today).exactSourceRequested()).isTrue();
        assertThat(AudioIntent.resolve("朗读一个原创故事", 5, today).exactSourceRequested()).isFalse();
        assertThat(AudioIntent.resolve("朗读这个故事的原文", 5, today).exactSourceRequested()).isTrue();
    }

    @Test void explicitLearningProgressIsOptionalAndLanguageRemainsIndependentOfTopic() {
        assertThat(AudioIntent.resolve("解释英语语法，用中文", 5, today).language()).isEqualTo("zh");
        assertThat(AudioIntent.resolve("按学习计划讲下一个知识点", 5, today).nextLearningPoint()).isTrue();
        assertThat(AudioIntent.resolve("介绍量子力学", 5, today).nextLearningPoint()).isFalse();
        assertThat(AudioIntent.resolve("写一段中英双语机场对话", 5, today).language()).isEqualTo("bilingual");
        for (String english : java.util.List.of("用简单英语讲一个侦探故事", "用基础英语讲一个故事", "用慢速英语讲这个主题", "用自然的英语讲故事", "用英语解释这个中文句子"))
            assertThat(AudioIntent.resolve(english, 5, today).language()).as(english).isEqualTo("en");
        for (String bilingual : java.util.List.of("用简单英语讲一个侦探故事，难词用中文解释", "用基础英语讲故事，难句翻译成中文", "用慢速英语讲故事，后面用中文逐句翻译", "用英语讲故事并附中文解释", "Tell a story in simple English with Chinese explanations of difficult words"))
            assertThat(AudioIntent.resolve(bilingual, 5, today).language()).as(bilingual).isEqualTo("bilingual");
        for (String chinese : java.util.List.of("把英文故事改成中文", "把英文故事翻译成中文，难词用中文解释", "用中文解释这个英文故事", "把双语对话改为中文"))
            assertThat(AudioIntent.resolve(chinese, 5, today).language()).as(chinese).isEqualTo("zh");
    }

    @Test void impossibleDatesAndUnboundedDurationFailBeforeGeneration() {
        assertThatThrownBy(() -> AudioIntent.resolve("90分钟故事", 5, today)).hasMessageContaining("1—60");
        assertThatThrownBy(() -> AudioIntent.resolve("2026-10-21 的最新新闻", 5, today)).hasMessageContaining("未来");
        assertThatThrownBy(() -> AudioIntent.resolve("2026-13-01 新闻", 5, today)).hasMessageContaining("日期无效");
    }

    @Test void readingContinuationIsExactButWritingTheNextChapterIsCreation() {
        for (String reading : java.util.List.of("接着读", "继续读这本书", "下一章", "读这本小说的下一章"))
            assertThat(AudioIntent.resolve(reading, 5, today).exactSourceRequested()).as(reading).isTrue();
        for (String writing : java.util.List.of("续写这个原创故事的下一章", "续写下一章", "创作下一章")) {
            var intent = AudioIntent.resolve(writing, 5, today);
            assertThat(intent.exactSourceRequested()).as(writing).isFalse();
            assertThat(intent.creative()).as(writing).isTrue();
        }
        assertThat(AudioIntent.resolve("换成英文，缩短至3分钟", 5, today).language()).isEqualTo("en");
    }

    @Test void currentDevelopmentsNeedFreshEvidenceEvenWithoutTheWordNews() {
        for (String current : java.util.List.of("现在有什么新进展", "目前有什么新的消息", "现在更新一下刚才的内容", "当前有哪些更新", "Any new developments right now?"))
            assertThat(AudioIntent.resolve(current, 5, today).freshEvidenceRequired()).as(current).isTrue();
        for (String unchanged : java.util.List.of("现在换成英文", "现在缩短刚才内容", "现在我不需要新的消息，只翻译刚才内容", "现在不要再更新，只缩短内容", "现在更新故事里的人物设定", "现在写一个原创故事"))
            assertThat(AudioIntent.resolve(unchanged, 5, today).freshEvidenceRequired()).as(unchanged).isFalse();
    }

    @Test void negatedLanguagesAndExplanationsDoNotOverrideTheRequestedLanguage() {
        for (String chinese : java.util.List.of("讲个中文故事，不要英语", "不用英文，讲中文新闻",
                "不要英文故事，讲中文新闻", "Tell a story in Chinese, not English", "Not in English, tell a story in Chinese"))
            assertThat(AudioIntent.resolve(chinese, 5, today).language()).as(chinese).isEqualTo("zh");
        for (String english : java.util.List.of("不要中文，用英文讲故事", "不用中文，讲英文新闻",
                "不要翻译成中文，用英文总结", "别把英文故事翻译成中文，保持英文",
                "用英语讲故事，不要用中文解释难词", "用英语讲故事，难词不用中文解释",
                "Tell a story in English, without Chinese explanations", "Tell an English story, not in Chinese"))
            assertThat(AudioIntent.resolve(english, 5, today).language()).as(english).isEqualTo("en");
        for (String bilingual : java.util.List.of("不要太复杂，用英语讲故事，难词用中文解释",
                "不用课程开场，用英语讲故事并附中文解释", "Tell a story in English with Chinese explanations of hard words"))
            assertThat(AudioIntent.resolve(bilingual, 5, today).language()).as(bilingual).isEqualTo("bilingual");
    }

    @Test void lastWeekUsesThePreviousCalendarWeekEvenWhenTodayIsMentioned() {
        var wednesday = LocalDate.of(2026, 10, 21);
        for (String request : java.util.List.of("上周的新闻，5分钟", "上一周的新闻，今天播给我听", "Last week's news")) {
            var intent = AudioIntent.resolve(request, 5, wednesday);
            assertThat(intent.fromDate()).as(request).isEqualTo(LocalDate.of(2026, 10, 12));
            assertThat(intent.throughDate()).as(request).isEqualTo(LocalDate.of(2026, 10, 18));
        }
        var monday = AudioIntent.resolve("上周新闻", 5, LocalDate.of(2026, 10, 19));
        assertThat(monday.fromDate()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(monday.throughDate()).isEqualTo(LocalDate.of(2026, 10, 18));
        var sunday = AudioIntent.resolve("上周新闻", 5, LocalDate.of(2026, 10, 18));
        assertThat(sunday.fromDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(sunday.throughDate()).isEqualTo(LocalDate.of(2026, 10, 11));
    }

    @Test void unrelatedNegativePreferencesDoNotNegateALaterEnglishDirective() {
        for (String request : java.util.List.of("不用检索用英语解释量子力学", "不要太复杂用英语讲解这个概念"))
            assertThat(AudioIntent.resolve(request, 5, today).language()).as(request).isEqualTo("en");
        for (String request : java.util.List.of("别把英文故事翻译成中文，保持英文", "不要将英文故事改成中文，用英文讲解"))
            assertThat(AudioIntent.resolve(request, 5, today).language()).as(request).isEqualTo("en");
    }

    @Test void threeDigitDayRangesAreHonoredAndInvalidRangesAreRejected() {
        for (String request : java.util.List.of("最近100天的新闻", "今天播报最近100天的新闻", "News from the past 100 days")) {
            var intent = AudioIntent.resolve(request, 5, today);
            assertThat(intent.fromDate()).as(request).isEqualTo(today.minusDays(99));
            assertThat(intent.throughDate()).as(request).isEqualTo(today);
        }
        assertThat(AudioIntent.resolve("最近7天新闻", 5, today).fromDate()).isEqualTo(today.minusDays(6));
        assertThat(AudioIntent.resolve("最近365天新闻", 5, today).fromDate()).isEqualTo(today.minusDays(364));
        for (String request : java.util.List.of("最近0天新闻", "过去-1天新闻", "最近366天新闻", "最近1000天新闻",
                "News from the last 999999999999999999999 days"))
            assertThatThrownBy(() -> AudioIntent.resolve(request, 5, today)).as(request).hasMessageContaining("1—365");
    }
}
