package com.bedouyu.audiorag.rag;

import java.util.regex.Pattern;

/**
 * 用户输入是「想听什么」的指令，不是检索词。直接拿整句去做词法检索，
 * “做成”“整理”“播报”“10 分钟”这些制作要求会变成匹配条件，挤掉真正的内容词。
 */
public final class QueryNormalizer {
    private static final Pattern DURATION = Pattern.compile(
            "(?i)(?:\\d{1,3}|[一二两三四五六七八九十百]{1,4})\\s*(?:分钟|分鐘|minutes?|mins?\\b)|半小时|一小时|half\\s+an\\s+hour|an?\\s+hour");
    private static final Pattern LANGUAGE = Pattern.compile(
            "(?i)(?:用|以|使用|输出|全程|全|纯|保持|换成|改为|改成|转成)?\\s*(?:中文|汉语|普通话|英文|英语|中英双语|双语)"
                    + "|\\b(?:in\\s+)?(?:simple\\s+|basic\\s+)?(?:chinese|mandarin|english)\\b|bilingual");
    private static final Pattern PRODUCTION_TAIL = Pattern.compile(
            "(?s)[，,；;]\\s*(?:(?:并|再|然后)\\s*)?(?:做成|制作|生成|整理成|写成|输出|转成|播报|朗读|读给我听|念给我听).*$");
    private static final Pattern LEADING_VERB = Pattern.compile(
            "^(?:请\\s*|麻烦\\s*|帮我\\s*|给我\\s*|为我\\s*|这次\\s*)*(?:整理|梳理|汇总|总结|播报|生成|制作|编写|写|讲|来|读|念|介绍|讲讲|说说)\\s*(?:一下|一段|一个|一篇|个|段|篇)?\\s*");
    private static final Pattern TRAILING_POLITE = Pattern.compile("(?s)[，,]?\\s*(?:谢谢|thanks?|please)\\s*[。.!！]?$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private QueryNormalizer() {
    }

    /**
     * 返回用于检索的查询串。剥离后过短（不足 2 个字符）时退回原文，
     * 避免把“讲个故事”这类请求削成空串而失去召回。
     */
    public static String forRetrieval(String instruction) {
        if (instruction == null || instruction.isBlank()) return "";
        String text = PRODUCTION_TAIL.matcher(instruction).replaceAll(" ");
        text = LEADING_VERB.matcher(text.strip()).replaceAll("");
        text = DURATION.matcher(text).replaceAll(" ");
        text = LANGUAGE.matcher(text).replaceAll(" ");
        text = TRAILING_POLITE.matcher(text).replaceAll(" ");
        text = WHITESPACE.matcher(text.replaceAll("^[\\s，,。.;；:：、]+|[\\s，,。.;；:：、]+$", "")).replaceAll(" ").strip();
        return text.length() < 2 ? instruction.strip() : text;
    }

    /** 由原文与清洗结果组成的检索变体；两者相同则只返回一项。 */
    public static java.util.List<String> variants(String instruction) {
        String normalized = forRetrieval(instruction);
        String original = instruction == null ? "" : instruction.strip();
        if (normalized.isEmpty()) return original.isEmpty() ? java.util.List.of() : java.util.List.of(original);
        return normalized.equals(original) ? java.util.List.of(normalized)
                : java.util.List.of(normalized, original);
    }
}
