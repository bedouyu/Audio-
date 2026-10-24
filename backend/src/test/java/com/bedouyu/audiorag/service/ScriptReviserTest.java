package com.bedouyu.audiorag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bedouyu.audiorag.ai.AgentModelGateway;
import com.bedouyu.audiorag.model.dto.AgentModelReply;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

    @Test void wholeRewritePromptHonorsTheReportedChineseProseRequest() {
        var model = configuredModel("雨水落在老院的青石板上，远处传来孩子的笑声。");
        String request = "在当前讲稿的基础上修改全篇为中文的中国散文";
        new ScriptReviser(model).rewriteWhole("Good morning. Repeat the English sentence twice.", request);

        String prompt = promptSentTo(model);
        assertThat(prompt).contains(request, "本次编辑的目标语言：中文", "中文散文", "文体由用户要求决定")
                .doesNotContain("不要翻译", "保持口语讲述风格", "保持原有语言（中文仍是中文、英文仍是英文）");
    }

    @Test void segmentPromptAllowsTranslationInsteadOfForbiddingIt() {
        var model = configuredModel("The rain fell softly over the old courtyard.");
        new ScriptReviser(model).revise("雨水落在老院里。", "翻译成英文散文");
        assertThat(promptSentTo(model)).contains("本次编辑的目标语言：英文", "用户明确要求翻译或更换语言时执行")
                .doesNotContain("不要翻译");
    }

    @Test void extensionPromptFollowsAnExplicitNewLanguageForTheNewPassage() {
        var model = configuredModel("春风走过老院，带来树叶轻轻的声音。");
        new ScriptReviser(model).extend("Spring came to the old courtyard.", "接下来用中文续写散文");
        assertThat(promptSentTo(model)).contains("本次编辑的目标语言：中文", "新增部分按新要求执行")
                .doesNotContain("严格沿用样例的格式、语言");
    }

    private AgentModelGateway configuredModel(String text) {
        var model = mock(AgentModelGateway.class);
        when(model.configured()).thenReturn(true);
        when(model.decide(anyList(), anyList(), any(), anyLong()))
                .thenReturn(new AgentModelReply(text, null, List.of(), "stop"));
        return model;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private String promptSentTo(AgentModelGateway model) {
        ArgumentCaptor<List<Map<String, Object>>> messages = ArgumentCaptor.forClass((Class) List.class);
        verify(model).decide(messages.capture(), anyList(), any(), anyLong());
        return messages.getValue().stream().map(message -> String.valueOf(message.get("content")))
                .reduce("", (left, right) -> left + "\n" + right);
    }
}
