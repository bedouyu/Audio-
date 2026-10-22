package com.bedouyu.audiorag.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SpeechTimingTest {
    @Test void chineseAndEnglishUseTheirOwnSpeakingRate() {
        // 195 字/分钟来自 12 段真实 WAV 的实测反算，不是先验猜测。
        assertThat(SpeechTiming.charactersPerMinute("你好世界")).isCloseTo(195.0, within(0.01));
        // 2 词 / 每分钟 150 词 = 0.8 秒；旧的字符口径会把它算成 10/260 分钟（约 2.3 秒）。
        assertThat(SpeechTiming.estimatedSeconds("hello world")).isCloseTo(0.8, within(0.001));
        assertThat(SpeechTiming.charactersPerMinute("hello world")).isCloseTo(750.0, within(0.01));
    }

    @Test void englishIsNotMeasuredWithTheChineseConstantAnymore() {
        String english = "word ".repeat(150).trim();
        String chinese = "字".repeat(195);

        assertThat(SpeechTiming.estimatedSeconds(english)).isCloseTo(60.0, within(0.5));
        assertThat(SpeechTiming.estimatedSeconds(chinese)).isCloseTo(60.0, within(0.5));
        // 旧口径按字符数除以 260 计算，会把这段英文高估一倍以上。
        double oldStyle = SpeechTiming.nonWhitespaceCharacters(english) * 60.0 / 260.0;
        assertThat(SpeechTiming.estimatedSeconds(english)).isLessThan(oldStyle / 2);
    }

    @Test void punctuationAndWhitespaceDoNotCountAsSpeakingTime() {
        String plain = "你好世界";
        String punctuated = "你好，世界。";

        assertThat(SpeechTiming.cjkCharacters(punctuated)).isEqualTo(4);
        assertThat(SpeechTiming.nonWhitespaceCharacters(punctuated)).isEqualTo(6);
        assertThat(SpeechTiming.estimatedSeconds(punctuated)).isEqualTo(SpeechTiming.estimatedSeconds(plain));
        // 但要念满同样的分钟数，带标点的原文需要更多字符。
        assertThat(SpeechTiming.charactersPerMinute(punctuated)).isGreaterThan(SpeechTiming.charactersPerMinute(plain));
    }

    @Test void mixedLanguageWeighsBothHalves() {
        String mixed = "你好 hello world";

        assertThat(SpeechTiming.cjkCharacters(mixed)).isEqualTo(2);
        assertThat(SpeechTiming.latinWords(mixed)).isEqualTo(2);
        assertThat(SpeechTiming.estimatedSeconds(mixed))
                .isCloseTo(2 * 60.0 / 195 + 2 * 60.0 / 150, within(0.001));
    }

    @Test void targetCharactersFollowsTheTextLanguage() {
        assertThat(SpeechTiming.targetCharacters("你好世界", 10)).isEqualTo(1950);
        assertThat(SpeechTiming.targetCharacters("hello world", 10)).isEqualTo(7500);
        assertThat(SpeechTiming.targetCharacters("", 10)).isEqualTo(1950);
        assertThat(SpeechTiming.targetCharacters("你好", 0)).isZero();
    }

    @Test void blankInputIsHandledWithoutDividingByZero() {
        assertThat(SpeechTiming.estimatedSeconds("")).isZero();
        assertThat(SpeechTiming.estimatedSeconds(null)).isZero();
        assertThat(SpeechTiming.charactersPerMinute("   ")).isEqualTo(195.0);
        assertThat(SpeechTiming.latinWords(null)).isZero();
    }
}
