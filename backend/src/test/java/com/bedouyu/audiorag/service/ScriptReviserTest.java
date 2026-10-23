package com.bedouyu.audiorag.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 模型偶尔会给讲稿加围栏、前缀或引号，必须清掉再落库。 */
class ScriptReviserTest {
    @Test void stripsCodeFencesAndLabelPrefixes() {
        assertThat(ScriptReviser.clean("```\n今天的天气很好。\n```")).isEqualTo("今天的天气很好。");
        assertThat(ScriptReviser.clean("修改后的讲稿：今天的天气很好。")).isEqualTo("今天的天气很好。");
        assertThat(ScriptReviser.clean("改写后：\n今天的天气很好。")).isEqualTo("今天的天气很好。");
        assertThat(ScriptReviser.clean("Revised script: Good morning.")).isEqualTo("Good morning.");
    }

    @Test void stripsWrappingQuotesOnlyWhenTheyPairUp() {
        assertThat(ScriptReviser.clean("“今天的天气很好。”")).isEqualTo("今天的天气很好。");
        assertThat(ScriptReviser.clean("今天的「重点」很好。")).isEqualTo("今天的「重点」很好。");
    }

    @Test void keepsNormalTextUntouched() {
        assertThat(ScriptReviser.clean("  今天的故事从一只小猫说起。  ")).isEqualTo("今天的故事从一只小猫说起。");
        assertThat(ScriptReviser.clean(null)).isEmpty();
    }
}
