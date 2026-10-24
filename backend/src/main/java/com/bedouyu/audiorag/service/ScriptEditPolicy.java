package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Editing inherits the current language unless the user explicitly requests another target. */
public final class ScriptEditPolicy {
    private static final Pattern LANGUAGE = Pattern.compile(
            "(?i)中文|汉语|普通话|英文|英语|\\bchinese\\b|\\bmandarin\\b|\\benglish\\b");
    private static final Pattern TARGET_PREFIX = Pattern.compile(
            "(?is).*(?:改成|改为|换成|换为|转成|转为|转换为|翻译成|翻译为|译成|译为|写成|写为|"
                    + "(?:修改|重写|改写)(?:全篇|全文|整篇|全部)?(?:为|成)|(?:全篇|全文|整篇|全部)(?:为|用)?|"
                    + "用|使用|以|输出|全程|全|纯|保持|保留|(?:\\bin|\\binto|\\bto)\\s+)"
                    + "(?:(?:纯|全|简单|简易|基础|自然|地道|口语化|简短|的|\\s)|simple\\s+|plain\\s+)*$");
    private static final Pattern LANGUAGE_GENRE = Pattern.compile(
            "(?i)^(?:的|版)?(?:散文|故事|小说|诗歌|文章|正文|讲稿|短文|对话|prose\\b|story\\b|essay\\b|script\\b|\\s+only\\b)");
    private static final Pattern BILINGUAL = Pattern.compile(
            "(?i)中英(?:文)?(?:双语|对照|交替)|双语|\\bbilingual\\b|"
                    + "(?:英文|英语|English)\\s*[-—→/、和与]\\s*(?:中文|汉语|Chinese)(?:\\s*[-—→/]\\s*(?:英文|英语|English))?|"
                    + "(?:中文|汉语|Chinese)\\s*[-—→/、和与]\\s*(?:英文|英语|English)|"
                    + "both\\s+(?:chinese\\s+and\\s+english|english\\s+and\\s+chinese)");
    private static final Pattern PRESERVE = Pattern.compile(
            "(?i)(?:保留|保持|沿用)(?:当前|原有|原来|原文|原稿)?(?:的)?(?:语言|语种)|"
                    + "(?:不|不要|别)(?:再)?(?:改变|更改|修改|改动)(?:当前|原有|原来|原文|原稿)?(?:的)?(?:语言|语种)|"
                    + "(?:keep|preserve|retain)\\s+(?:the\\s+)?(?:original|current)\\s+language");
    private static final Pattern LENGTH_CHANGE = Pattern.compile(
            "(?i)缩短|缩写|精简|压缩|扩写|扩展|加长|增加.{0,8}(?:篇幅|字数|时长)|减少.{0,8}(?:篇幅|字数|时长)|"
                    + "字数|字以内|字左右|\\d+\\s*(?:字|词|分钟|秒)|时长|篇幅|"
                    + "\\b(?:shorten|shorter|lengthen|longer|expand|condense|duration|word\\s+count)\\b|"
                    + "\\d+\\s*(?:words?|minutes?|seconds?)\\b");

    private ScriptEditPolicy() { }

    public static String targetLanguage(String currentText, String instruction) {
        String original = SpeechService.detectLanguage(currentText);
        return explicitTarget(instruction, original).stream().max(Comparator.comparingInt(Target::end))
                .map(Target::language).orElse(original);
    }

    /** Manual text is the user's own language choice; model output must satisfy the edit instruction. */
    public static void validateLanguage(String currentText, String editedText, String instruction, boolean manual) {
        String actual = SpeechService.detectLanguage(editedText);
        if (manual) return;
        String target = targetLanguage(currentText, instruction);
        if (target.equals(actual) || "zh".equals(target) && mainlyChineseWithNames(editedText)) return;
        throw new ApiException(HttpStatus.BAD_REQUEST, "修改后的讲稿未符合本次要求的语言（"
                + label(target) + "），请重新修改");
    }

    /** Translation and requested length changes cannot use a same-language character-count ratio. */
    public static boolean allowsLengthChange(String currentText, String instruction) {
        String request = instruction == null ? "" : instruction;
        return !targetLanguage(currentText, request).equals(SpeechService.detectLanguage(currentText))
                || positiveMatch(request, Pattern.compile("(?i)翻译|译成|译为|\\btranslate\\b|\\btranslation\\b"))
                || positiveMatch(request, LENGTH_CHANGE);
    }

    public static String promptHint(String currentText, String instruction) {
        return "本次编辑的目标语言：" + label(targetLanguage(currentText, instruction))
                + "。用户明确要求换语言时按目标语言改写或翻译；未明确换语言时沿用原稿语言。"
                + "用户指定的体裁、风格、结构和篇幅优先，散文等文学体裁不强制改成口语讲解。"
                + "只输出可朗读的正文，不附带语言标签、审核结论或制作说明。";
    }

    private static List<Target> explicitTarget(String instruction, String original) {
        String text = instruction == null ? "" : instruction;
        List<Target> result = new ArrayList<>();
        var language = LANGUAGE.matcher(text);
        while (language.find()) {
            String before = clausePrefix(text, language.start());
            String after = text.substring(language.end());
            if ((TARGET_PREFIX.matcher(before).matches() || LANGUAGE_GENRE.matcher(after).find())
                    && !negated(text, language.start())) {
                String word = language.group().toLowerCase(java.util.Locale.ROOT);
                result.add(new Target(language.end(), List.of("英文", "英语", "english").contains(word) ? "en" : "zh"));
            }
        }
        var bilingual = BILINGUAL.matcher(text);
        while (bilingual.find()) if (!negated(text, bilingual.start())) result.add(new Target(bilingual.end(), "bilingual"));
        var preserve = PRESERVE.matcher(text);
        while (preserve.find()) {
            if (preserve.group().matches("^(?:不|不要|别).*") || !negated(text, preserve.start()))
                result.add(new Target(preserve.end(), original));
        }
        return result;
    }

    private static boolean positiveMatch(String text, Pattern expression) {
        var matcher = expression.matcher(text);
        while (matcher.find()) if (!negated(text, matcher.start())) return true;
        return false;
    }

    private static boolean negated(String text, int start) {
        String before = clausePrefix(text, start);
        // Restrict negation to a nearby directive; a mention of the source language is not a target.
        return before.matches("(?is).*(?:不要|不用|无需|不需要|不想|禁止|避免|别|不)"
                + "(?:再|重新|给我|为我|把|将|改成|改为|换成|换为|转成|转为|转换为|翻译成|翻译为|"
                + "译成|写成|用|使用|以|输出|保持|保留|全篇|全文|整篇|全部|为|成|纯|全|的|\\s)*$")
                || before.matches("(?is)^(?:请|我|这次|现在|千万|\\s)*(?:不要|不用|无需|不想|不需要|别|禁止|避免)"
                + "(?:再|重新)?(?:把|将)[^，,。；;\\n]{0,50}$")
                || before.matches("(?is).*\\b(?:not|no|without|avoid|never|do\\s+not|don['’]t)"
                + "(?:\\s+(?:use|using|write|rewrite|translate|switch|change|to|into|in|any|plain|simple))*\\s*$");
    }

    private static String clausePrefix(String text, int end) {
        return text.substring(Math.max(0, end - 80), end)
                .replaceFirst("(?s)^.*[，,。；;\\n!?！？]", "");
    }

    private static boolean mainlyChineseWithNames(String text) {
        long han = text.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN).count();
        long latin = text.codePoints().filter(cp -> Character.isLetter(cp)
                && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN).count();
        return han >= 20 && latin > 0 && latin <= 24 && han >= latin * 4;
    }

    private static String label(String language) {
        return switch (language) { case "en" -> "英文"; case "bilingual" -> "中英双语"; default -> "中文"; };
    }

    private record Target(int end, String language) { }
}
