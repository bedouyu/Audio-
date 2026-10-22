package com.bedouyu.audiorag.service.content;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AudioIntentHintTest {
    private final LocalDate today = LocalDate.of(2026, 10, 22);

    @Test void chineseNewsRequestCarriesLanguageDurationAndFreshness() {
        var intent = AudioIntent.resolve("整理今天的时政，做成 10 分钟简报", 5, today);

        assertThat(intent.durationMinutes()).isEqualTo(10);
        assertThat(intent.language()).isEqualTo("zh");
        assertThat(intent.freshEvidenceRequired()).isTrue();
        assertThat(intent.lengthHint()).isEqualTo("约 2600 个汉字");
        assertThat(intent.agentHint())
                .contains("目标时长≈10 分钟")
                .contains("需要本次实时证据=是")
                .contains("可核对日期范围=");
    }

    @Test void englishRequestIsHintedInWordsNotCharacters() {
        var intent = AudioIntent.resolve("tell me a 5 minute bedtime story in English", 5, today);

        assertThat(intent.language()).isEqualTo("en");
        assertThat(intent.durationMinutes()).isEqualTo(5);
        assertThat(intent.lengthHint()).isEqualTo("约 750 个英文单词");
        assertThat(intent.agentHint()).contains("语言=English");
    }

    @Test void bilingualRequestSplitsTheTargetBetweenBothLanguages() {
        var intent = AudioIntent.resolve("用中英双语讲一段 10 分钟的睡前故事", 5, today);

        assertThat(intent.language()).isEqualTo("bilingual");
        assertThat(intent.lengthHint()).isEqualTo("中文约 1300 字 + 英文约 750 词");
    }

    @Test void explicitMinutesOnlyAppliesWhenTheUserWroteOne() {
        assertThat(AudioIntent.explicitMinutes("讲个故事")).isEmpty();
        assertThat(AudioIntent.explicitMinutes("讲个十分钟的故事")).hasValue(10);
        assertThat(AudioIntent.explicitMinutes("make it 20 minutes")).hasValue(20);
        assertThat(AudioIntent.explicitMinutes("半小时的新闻")).hasValue(30);
        assertThat(AudioIntent.explicitMinutes(null)).isEmpty();
    }

    @Test void hintDoesNotTurnTheServerDefaultIntoARequirement() {
        var intent = AudioIntent.resolve("帮我讲个量子力学的故事", 5, today);

        assertThat(intent.agentHint())
                .contains("用户没有指定时长")
                .contains("不是硬要求")
                .doesNotContain("目标时长≈")
                .doesNotContain("target_minutes=");
    }
}
