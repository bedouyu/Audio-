package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.ai.AgentModelGateway;
import com.bedouyu.audiorag.common.ApiException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 按用户的修改意见改写一段已有讲稿。
 * 这里刻意只给模型"这一段原文 + 修改要求"，并要求保留未提及的部分，
 * 避免它像普通生成那样重写整篇。
 */
@Component
public class ScriptReviser {
    private static final Logger log = LoggerFactory.getLogger(ScriptReviser.class);
    private static final String SYSTEM = String.join("\n",
            "你是音频讲稿的编辑。用户会给你一段现有讲稿和一条修改要求。",
            "规则：",
            "1. 只按修改要求改动相关内容，未提及的部分保持原样，不要顺手润色全文。",
            "2. 保持原有语言（中文仍是中文、英文仍是英文），不要翻译、不要加标题或说明。",
            "3. 保持口语讲述风格，不要添加 Markdown、编号列表或旁白。",
            "4. 直接输出修改后的讲稿全文，不要任何前后缀、解释或引号包裹。",
            "5. 如果修改要求与该段内容无关，就原样返回这段讲稿。");

    private static final String WHOLE_SYSTEM = String.join("\n",
            "你是音频讲稿的编辑。用户会给你一整篇现有讲稿和一条修改要求。",
            "规则：",
            "1. 按修改要求重排整篇：可以删掉不要的部分（比如开场白、串场、结尾总结），也可以调整每句的组织顺序。",
            "2. 只改被要求改的部分，没提到的内容保持原意和原语言，不要翻译、不要新增解释。",
            "3. 保持口语讲述风格，不要添加 Markdown、编号列表、括号旁白或任何前后缀说明。",
            "4. 输出重写后的整篇讲稿正文；段落之间用空行分隔，方便之后再分段。",
            "5. 严格按修改要求执行，不要因为“看起来更完整”而自行加回被要求删除的内容。");

    private final AgentModelGateway model;

    public ScriptReviser(AgentModelGateway model) { this.model = model; }

    /** 返回改写后的全文；模型不可用或返回空时抛错，不静默返回原文。 */
    public String revise(String script, String instruction) {
        if (!model.configured())
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "模型未配置，无法按意见修改讲稿；可以先手动编辑再保存");
        var messages = List.of(
                Map.<String, Object>of("role", "system", "content", SYSTEM),
                Map.<String, Object>of("role", "user", "content",
                        "当前讲稿：\n<<<\n" + script + "\n>>>\n\n修改要求：" + instruction));
        try {
            var reply = model.decide(messages, List.of(), token -> { }, 180_000L);
            String text = clean(reply == null ? null : reply.content());
            if (text.isBlank())
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "模型没有返回修改后的讲稿，请重试或手动编辑");
            return text;
        } catch (ApiException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            log.warn("讲稿改写失败：{}", failure.toString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "讲稿改写失败：" + failure.getMessage());
        }
    }

    /**
     * 整篇重写：用户要改的是格式/结构（去掉开场白、按"英语-汉语-英语"循环等），
     * 不是局部措辞，所以给它整篇原文并允许它重排内容。分段由调用方负责。
     */
    public String rewriteWhole(String script, String instruction) {
        if (!model.configured())
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "模型未配置，无法按意见重写讲稿；可以手动编辑后保存");
        var messages = List.of(
                Map.<String, Object>of("role", "system", "content", WHOLE_SYSTEM),
                Map.<String, Object>of("role", "user", "content",
                        "当前整篇讲稿：\n<<<\n" + script + "\n>>>\n\n整篇修改要求：" + instruction));
        try {
            var reply = model.decide(messages, List.of(), token -> { }, 240_000L);
            String text = clean(reply == null ? null : reply.content());
            if (text.isBlank())
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "模型没有返回重写后的讲稿，请重试或手动编辑");
            return text;
        } catch (ApiException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            log.warn("整篇讲稿重写失败：{}", failure.toString());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "整篇讲稿重写失败：" + failure.getMessage());
        }
    }

    /** 去掉模型偶尔带上的代码围栏、"修改后的讲稿：" 前缀和包裹引号。 */
    static String clean(String raw) {
        if (raw == null) return "";
        String text = raw.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            text = firstNewline < 0 ? "" : text.substring(firstNewline + 1);
            if (text.endsWith("```")) text = text.substring(0, text.length() - 3);
            text = text.strip();
        }
        for (String prefix : List.of("修改后的讲稿：", "修改后：", "改写后：", "Revised script:")) {
            if (text.startsWith(prefix)) text = text.substring(prefix.length()).strip();
        }
        if (text.length() > 1 && "\"“" .indexOf(text.charAt(0)) >= 0 && "\"”".indexOf(text.charAt(text.length() - 1)) >= 0)
            text = text.substring(1, text.length() - 1).strip();
        return text;
    }
}
