package com.bedouyu.audiorag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bedouyu.audiorag.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ScriptEditPolicyTest {
    private static final String ENGLISH = "Good morning. The old house stood quietly beside the river, waiting for spring.";
    private static final String CHINESE = "春天又回到了老院。青石板上的雨水映着天光，墙边的树轻轻摇动，仿佛在等一个离家多年的人。";

    @Test void exactReportedInstructionSwitchesTheEntireEnglishScriptToChinese() {
        String request = "在当前讲稿的基础上修改全篇为中文的中国散文";
        assertThat(ScriptEditPolicy.targetLanguage(ENGLISH, request)).isEqualTo("zh");
        assertThatCode(() -> ScriptEditPolicy.validateLanguage(ENGLISH, CHINESE, request, false)).doesNotThrowAnyException();
        assertThat(ScriptEditPolicy.allowsLengthChange(ENGLISH, request)).isTrue();
    }

    @Test void explicitChineseAndEnglishTargetsWorkInEitherDirection() {
        assertThat(ScriptEditPolicy.targetLanguage(CHINESE, "把全文改成英文散文")).isEqualTo("en");
        assertThat(ScriptEditPolicy.targetLanguage(CHINESE, "Rewrite the script in English prose.")).isEqualTo("en");
        assertThat(ScriptEditPolicy.targetLanguage(ENGLISH, "Rewrite it in Chinese.")).isEqualTo("zh");
        assertThat(ScriptEditPolicy.targetLanguage(ENGLISH, "全篇中文，不要教学说明")).isEqualTo("zh");
        assertThat(ScriptEditPolicy.targetLanguage(ENGLISH, "Chinese only.")).isEqualTo("zh");
        assertThatCode(() -> ScriptEditPolicy.validateLanguage(CHINESE, ENGLISH, "用英文重写", false)).doesNotThrowAnyException();
        assertThatCode(() -> ScriptEditPolicy.validateLanguage(ENGLISH, CHINESE, "不要英文，改成中文", false)).doesNotThrowAnyException();
    }

    @Test void explicitBilingualFormatsRequireBothLanguages() {
        assertThat(ScriptEditPolicy.targetLanguage(ENGLISH, "改为中英双语")).isEqualTo("bilingual");
        assertThat(ScriptEditPolicy.targetLanguage(CHINESE, "按英语-汉语-英语两遍的格式重写")).isEqualTo("bilingual");
        assertThatCode(() -> ScriptEditPolicy.validateLanguage(ENGLISH, ENGLISH + "\n" + CHINESE,
                "改为中英文对照", false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ScriptEditPolicy.validateLanguage(ENGLISH, CHINESE, "改为中英双语", false))
                .hasMessageContaining("中英双语");
    }

    @Test void defaultAndOriginalLanguageInstructionsPreserveTheCurrentLanguage() {
        assertThat(ScriptEditPolicy.targetLanguage(ENGLISH, "把开头写得生动一些")).isEqualTo("en");
        assertThat(ScriptEditPolicy.targetLanguage(CHINESE, "把开头写得生动一些")).isEqualTo("zh");
        assertThat(ScriptEditPolicy.targetLanguage(ENGLISH,
                "这份英文原稿改成中国散文，保留原有语言")).isEqualTo("en");
        assertThat(ScriptEditPolicy.targetLanguage(CHINESE,
                "参考英文原稿的节奏，保留原有语言")).isEqualTo("zh");
        assertThat(ScriptEditPolicy.targetLanguage(ENGLISH, "保留原有语言，只调整段落顺序")).isEqualTo("en");
        assertThatThrownBy(() -> ScriptEditPolicy.validateLanguage(ENGLISH, CHINESE, "只调整段落顺序", false))
                .isInstanceOfSatisfying(ApiException.class,
                        failure -> assertThat(failure.status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test void negatedLanguageChangesDoNotBecomePositiveTargets() {
        assertThat(ScriptEditPolicy.targetLanguage(CHINESE, "不要改成英文，只修标点")).isEqualTo("zh");
        assertThat(ScriptEditPolicy.targetLanguage(CHINESE, "不要把这篇改成英文，保留原有语言")).isEqualTo("zh");
        assertThat(ScriptEditPolicy.targetLanguage(ENGLISH, "不要把英文翻译成中文")).isEqualTo("en");
        assertThat(ScriptEditPolicy.targetLanguage(CHINESE, "Do not rewrite in English.")).isEqualTo("zh");
        assertThat(ScriptEditPolicy.targetLanguage(ENGLISH, "不要改变原有语言，只改标题")).isEqualTo("en");
        assertThat(ScriptEditPolicy.targetLanguage(ENGLISH, "不要中英双语，全文用中文")).isEqualTo("zh");
    }

    @Test void chineseProseCanContainAFewEnglishProperNamesWithoutBecomingAnEnglishBody() {
        String withNames = CHINESE.repeat(3) + "我在信封上看见了 Alice 的名字。";
        assertThatCode(() -> ScriptEditPolicy.validateLanguage(ENGLISH, withNames, "整篇改为中文散文", false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ScriptEditPolicy.validateLanguage(ENGLISH, ENGLISH.repeat(2) + CHINESE,
                "整篇改为中文散文", false)).hasMessageContaining("中文");
    }

    @Test void aModelReturningTheOriginalLanguageCannotPassAnExplicitLanguageChange() {
        assertThatThrownBy(() -> ScriptEditPolicy.validateLanguage(ENGLISH, ENGLISH,
                "在当前讲稿的基础上修改全篇为中文的中国散文", false))
                .hasMessageContaining("未符合本次要求的语言").hasMessageContaining("中文");
    }

    @Test void manuallySuppliedTextIsAnExplicitLanguageChoice() {
        assertThatCode(() -> ScriptEditPolicy.validateLanguage(ENGLISH, CHINESE, null, true)).doesNotThrowAnyException();
        assertThatCode(() -> ScriptEditPolicy.validateLanguage(CHINESE, ENGLISH, null, true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ScriptEditPolicy.validateLanguage(CHINESE, "日本語の文章です。", null, true))
                .hasMessageContaining("无法自动识别");
    }

    @Test void characterRatioDoesNotPreventRequestedTranslationOrLengthChanges() {
        assertThat(ScriptEditPolicy.allowsLengthChange(ENGLISH, "翻译成中文")).isTrue();
        assertThat(ScriptEditPolicy.allowsLengthChange(CHINESE, "缩短到二十字以内")).isTrue();
        assertThat(ScriptEditPolicy.allowsLengthChange(CHINESE, "Expand it to 500 words.")).isTrue();
        assertThat(ScriptEditPolicy.allowsLengthChange(CHINESE, "不要翻译，只修标点")).isFalse();
        assertThat(ScriptEditPolicy.allowsLengthChange(CHINESE, "只改错别字，保留原有语言")).isFalse();
    }
}
