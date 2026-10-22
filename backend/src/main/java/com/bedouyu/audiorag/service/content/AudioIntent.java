package com.bedouyu.audiorag.service.content;

import com.bedouyu.audiorag.common.SpeechTiming;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/** Small deterministic hints; the complete instruction remains the model's contract. */
public record AudioIntent(String instruction, String language, int durationMinutes,
        boolean freshEvidenceRequired, boolean creative, boolean exactSourceRequested,
        boolean nextLearningPoint, LocalDate asOfDate, LocalDate fromDate, LocalDate throughDate) {
    private static final Pattern MINUTES = Pattern.compile("(?i)(\\d{1,3})\\s*(?:分钟|分鐘|分(?:钟)?音频|minutes?|mins?\\b)");
    private static final Pattern CHINESE_MINUTES = Pattern.compile("([一二两三四五六七八九十百]{1,4})\\s*分钟");
    private static final Pattern DATE = Pattern.compile("(20\\d{2})[-年/.](\\d{1,2})[-月/.](\\d{1,2})日?");
    private static final Pattern RELATIVE_DAYS = Pattern.compile("(?i)(?:过去|最近|近|past|last)\\s*([+-]?\\d+)\\s*(?:天|days?\\b)");

    public static AudioIntent resolve(String instruction, int fallbackMinutes) {
        return resolve(instruction, fallbackMinutes, LocalDate.now(ZoneId.of("Asia/Shanghai")));
    }

    public static AudioIntent resolve(String instruction, int fallbackMinutes, LocalDate today) {
        if (instruction == null || instruction.isBlank()) throw new IllegalArgumentException("请描述想听的内容");
        String text = instruction.strip(), lower = text.toLowerCase(Locale.ROOT);
        boolean creative = matches(text, "(?i)(原创|虚构|续写|继续写|接着写|编(?:写|个|一|一个)|写(?:一|个|出|篇|段)|创作|讲(?:一|个)|来(?:一|个)).{0,30}(故事|小说|童话|剧本|对话)|(?i)(write|create|generate|compose|tell|original|fictional).{0,40}(story|fiction|dialogue)|bedtime story");
        boolean exact = matches(text, "(?i)(逐字|原样|照着|照读|原文|全文|朗读|有声书|读给我听|念给我听|接着读|继续读|读这本|下一章)|read\\s+(?:it\\s+)?aloud|verbatim|narrate\\s+(?:the|my|this)\\s+(?:book|chapter|text)");
        if (matches(text, "(?i)(续写|继续写|接着写|创作|编写|write|create|generate|compose).{0,20}(下一章|第[一二三四五六七八九十百0-9]+章|chapter)")) creative = true;
        // An original story can be narrated aloud without pretending to reproduce an existing source.
        if (creative && !matches(text, "(?i)(导入|上传|这份|这篇|这本|这段|已有|原文|全文|source|uploaded|imported|verbatim)")) exact = false;
        if (matches(text, "(?i)续写|继续写|接着写|创作|编写|生成|write|create|generate|compose")
                && !matches(text, "(?i)逐字|原文|全文|朗读|照读|接着读|继续读|read\\s+aloud|verbatim")) exact = false;
        if (matches(text, "(?i)(生成|写|创作|编写|create|generate|write).{0,20}(朗读练习|英文短文|reading passage)")
                && !matches(text, "导入|上传|原文|全文|这篇|这段")) exact = false;
        boolean news = !creative && positiveMatches(text, "(?i)时政|时事|新闻(?!学|史|写作|行业)|国际局势|时局|current affairs|\\bnews\\b|headlines");
        boolean relative = matches(text, "(?i)(现在|目前|当前|今天|今日|昨天|昨日|最新|近期|最近|过去\\s*\\d+\\s*天|today|yesterday|latest|currently|\\bnow\\b|current|past\\s+\\d+\\s+days)");
        boolean historicalExplanation = matches(text, "历史背景|历史事件|新闻学|新闻史|新闻写作") && !relative;
        boolean changingFacts = positiveMatches(text, "(?i)最新|新(?:的)?进展|新(?:的)?消息|近期进展|近况|局势|热点|市场动态|政策动态|更新|current affairs|latest|headlines|new developments|updates?|what(?:'s|\\s+is)\\s+new");
        boolean creativeRevision = !news && matches(text, "(?i)(更新|修改|改写|调整).{0,20}(故事|小说|情节|人物|角色|对话|剧本)");
        boolean fresh = !exact && !creative && !creativeRevision && (news || relative && changingFacts) && !historicalExplanation;
        String language = language(text, creative);

        int minutes = fallbackMinutes > 0 ? fallbackMinutes : 5;
        var numeric = MINUTES.matcher(text);
        var chinese = CHINESE_MINUTES.matcher(text);
        if (numeric.find()) minutes = Integer.parseInt(numeric.group(1));
        else if (chinese.find()) minutes = chineseNumber(chinese.group(1));
        else if (text.contains("半小时") || lower.contains("half an hour")) minutes = 30;
        else if (text.contains("一小时") || lower.contains("one hour")) minutes = 60;
        if (minutes < 1 || minutes > 60) throw new IllegalArgumentException("当前每次音频支持 1—60 分钟，请缩短或拆分请求");

        LocalDate from = today.minusDays(7), through = today;
        var dates = DATE.matcher(text);
        var days = RELATIVE_DAYS.matcher(text);
        if (dates.find()) {
            from = date(dates); through = from;
            if (dates.find()) through = date(dates);
        } else if (days.find()) from = today.minusDays(relativeDays(days.group(1)) - 1L);
        else if (matches(text, "(?i)上(?:一|个)?周|上(?:个)?星期|\\b(?:last|previous)\\s+week\\b")) {
            from = today.minusDays(today.getDayOfWeek().getValue() - 1L).minusWeeks(1);
            through = from.plusDays(6);
        } else if (matches(text, "(?i)本周|这周|\\bthis\\s+week\\b")) from = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        else if (text.contains("本月")) from = today.withDayOfMonth(1);
        else if (matches(text, "(?i)昨天|昨日|yesterday")) from = through = today.minusDays(1);
        else if (matches(text, "(?i)今天|今日|today")) from = through = today;
        if (through.isBefore(from)) throw new IllegalArgumentException("新闻时间范围的结束日期不能早于开始日期");
        if (fresh && through.isAfter(today)) throw new IllegalArgumentException("不能播报未来日期尚未发布的新闻");
        return new AudioIntent(text, language, minutes, fresh, creative, exact,
                positiveMatches(text, "按(?:照)?(?:既有|已有|当前)?学习计划|下一个知识点|下一知识点|next learning point"),
                today, from, through);
    }

    public String timeScope() { return fromDate + " 至 " + throughDate + "（北京时间，检索截至 " + asOfDate + "）"; }
    public String languageLabel() { return switch (language) { case "en" -> "English"; case "bilingual" -> "中英双语"; default -> "中文"; }; }
    public String searchQuery() { return instruction + " after:" + fromDate.minusDays(1) + " before:" + throughDate.plusDays(1); }

    /** 用户是否显式写了时长；没写时不要拿默认值去要求模型。 */
    public static OptionalInt explicitMinutes(String text) {
        if (text == null || text.isBlank()) return OptionalInt.empty();
        var numeric = MINUTES.matcher(text);
        if (numeric.find()) return OptionalInt.of(Integer.parseInt(numeric.group(1)));
        var chinese = CHINESE_MINUTES.matcher(text);
        if (chinese.find()) return OptionalInt.of(chineseNumber(chinese.group(1)));
        if (text.contains("半小时")) return OptionalInt.of(30);
        if (text.contains("一小时")) return OptionalInt.of(60);
        return OptionalInt.empty();
    }

    /** 应写篇幅：按目标语言自身的语速换算，英文不能沿用中文的字符口径。 */
    public String lengthHint() {
        return switch (language) {
            case "en" -> "约 " + Math.round(durationMinutes * SpeechTiming.LATIN_WORDS_PER_MINUTE) + " 个英文单词";
            case "bilingual" -> "中文约 " + perMinute(130) + " 字 + 英文约 " + perMinute(75) + " 词";
            default -> "约 " + Math.round(durationMinutes * SpeechTiming.CJK_CHARACTERS_PER_MINUTE) + " 个汉字";
        };
    }

    /** 下限：实测写手会稳定少写 10—15%，只给目标值时更容易偏短。 */
    public String lengthFloor() {
        return switch (language) {
            case "en" -> Math.round(durationMinutes * SpeechTiming.LATIN_WORDS_PER_MINUTE * 0.9) + " 个英文单词";
            case "bilingual" -> perMinute(120) + " 字加 " + perMinute(68) + " 词";
            default -> Math.round(durationMinutes * SpeechTiming.CJK_CHARACTERS_PER_MINUTE * 0.9) + " 个汉字";
        };
    }

    /** 注入主控的服务端解析提示：只陈述事实与硬约束，不替用户改变要求。 */
    public String agentHint() {
        boolean explicit = explicitMinutes(instruction).isPresent();
        StringBuilder hint = new StringBuilder("服务端确定性解析（供参考，用户原话与后续更正优先）：语言=")
                .append(languageLabel());
        if (explicit) {
            hint.append("；目标时长≈").append(durationMinutes).append(" 分钟，正文应").append(lengthHint())
                    .append("（不少于 ").append(lengthFloor()).append("）");
        } else {
            // 用户没写时长时不能把服务端默认值伪装成要求，否则会缩窄结果。
            hint.append("；用户没有指定时长，篇幅按内容需要决定（服务端默认约 ").append(durationMinutes).append(" 分钟，不是硬要求）");
        }
        hint.append("；需要本次实时证据=").append(freshEvidenceRequired ? "是" : "否")
                .append("；创作类=").append(creative ? "是" : "否")
                .append("；逐字原文=").append(exactSourceRequested ? "是" : "否");
        if (freshEvidenceRequired) hint.append("；可核对日期范围=").append(timeScope());
        hint.append(explicit
                ? "。语言与时长是硬约束：交付正文前用 estimate_duration 以 target_minutes=" + durationMinutes
                        + " 核对，偏差超过 20% 先自行调整再交付。"
                : "。语言是硬约束；用户没有要求时长，不要为凑时长删减必要内容。");
        return hint.toString();
    }

    private long perMinute(double charactersPerMinute) { return Math.round(durationMinutes * charactersPerMinute); }

    private static boolean matches(String text, String expression) { return Pattern.compile(expression).matcher(text).find(); }
    private static String language(String text, boolean creative) {
        String qualifiers = "(?:(?:简单|简易|基础|初级|慢速|自然|清晰|通俗|地道|口语化)(?:的)?[\\s/、]*)*";
        boolean englishDirective = positiveLanguageMatches(text, "(?i)(?:用|使用|以|输出|全程|全|纯|讲|说|读|听|来|一段|一个|一篇|分钟|版|保持|换成|改为|改成|转成|翻译成|译成|写成)\\s*"
                + qualifiers + "(?:英文|英语)|\\bin\\s+(?:(?:simple|basic|slow|natural|plain)\\s+)*english\\b", true);
        boolean chineseDirective = positiveLanguageMatches(text, "(?i)(?:用|使用|以|输出|全程|全|纯|讲|说|读|听|保持|换成|改为|改成|转成|翻译成|译成|写成)\\s*"
                + qualifiers + "(?:中文|汉语|普通话)|\\bin\\s+(?:chinese|mandarin)\\b", true);
        boolean englishGenre = positiveLanguageMatches(text, "(?i)(?:英文|英语)(?:版|故事|小说|播客|新闻|对话|短文|内容|音频)|english\\s+(?:story|podcast|news|dialogue|audio|conversation)", false);
        boolean englishBody = englishDirective || creative && positiveLanguageMatches(text, "(?i)英文|英语|\\benglish\\b", false)
                || englishGenre && !chineseDirective;
        String partialTargets = "难词|生词|难句|长句|复杂句子|不懂的句子|困难的句子|difficult\\s+(?:words|sentences)|hard\\s+(?:words|sentences)";
        // A complete rewrite to Chinese differs from translating only the difficult words in an English body.
        var rewrite = Pattern.compile("(?i)(?:改成|改为|换成|转成|转换为|翻译成|译成|写成)\\s*" + qualifiers + "(?:中文|汉语|普通话)").matcher(text);
        while (rewrite.find()) {
            if (negatedLanguagePrefix(text, rewrite.start(), true)) continue;
            String prefix = text.substring(Math.max(0, rewrite.start() - 28), rewrite.start());
            int boundary = Math.max(prefix.lastIndexOf('，'), Math.max(prefix.lastIndexOf(','), prefix.lastIndexOf('\n')));
            if (!matches(prefix.substring(boundary + 1), "(?i)(?:" + partialTargets + ")[^，,。；;\\n]{0,16}$")) return "zh";
        }
        if (positiveLanguageMatches(text, "(?i)中英(?:文)?双语|双语|bilingual|both\\s+(?:chinese\\s+and\\s+english|english\\s+and\\s+chinese)", false)) return "bilingual";
        boolean chineseSupport = positiveLanguageMatches(text, "(?i)(?:用|以)?\\s*(?:中文|汉语)\\s*(?:来|逐句|简单地)?\\s*(?:解释|翻译|注释|释义|讲解|总结)"
                + "|(?:" + partialTargets + ")[^，,。；;\\n]{0,24}(?:中文|汉语)"
                + "|chinese\\s+(?:explanations?|translations?|glosses)"
                + "|(?:explain|translate)[^,.;\\n]{0,40}(?:difficult|hard|unfamiliar)[^,.;\\n]{0,30}in\\s+chinese", true);
        if (englishBody && chineseSupport) return "bilingual";
        if (chineseDirective) return "zh";
        return englishBody || englishGenre ? "en" : "zh";
    }
    private static boolean positiveLanguageMatches(String text, String expression, boolean action) {
        var matcher = Pattern.compile(expression).matcher(text);
        while (matcher.find()) {
            if (matches(matcher.group(), "(?i)不要|不用|无需|不需要|不想听|\\b(?:not|without|avoid|do\\s+not|don['’]t)\\b")) continue;
            if (!negatedLanguagePrefix(text, matcher.start(), action)) return true;
        }
        return false;
    }
    private static boolean negatedLanguagePrefix(String text, int start, boolean action) {
        String before = text.substring(Math.max(0, start - 60), start)
                .replaceFirst("(?s)^.*[，,。；;\\n!?！？]", "").strip();
        if (matches(before, "(?is)(?:不要|不用|无需|不想(?:要|听)?|不需要|别|跳过|不|非)"
                + "(?:再|重新|给我|为我|听|讲|说|读|按|照|用|使用|以|输出|任何|全程|简单|基础|慢速|的|\\s)*$"
                + "|\\b(?:not|no|without|avoid|skip|instead\\s+of|don['’]t|do\\s+not|never)"
                + "(?:\\s+(?:use|using|speak|speaking|write|include|any|in|simple|plain))*\\s*$")) return true;
        // Only an explicit object construction lets a negation reach across intervening text.
        return action && matches(before, "(?is)^(?:请\\s*|我\\s*|这次\\s*|现在\\s*|千万\\s*)*"
                + "(?:不要|不用|无需|不想|不需要|别|禁止|避免)(?:再|重新|继续)?(?:把|将)[^，,。；;\\n]{0,40}$");
    }
    private static int relativeDays(String value) {
        try {
            int days = Integer.parseInt(value);
            if (days >= 1 && days <= 365) return days;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("最近新闻时间范围支持 1—365 天，请缩短范围或指定起止日期");
    }
    private static boolean positiveMatches(String text, String expression) {
        var matcher = Pattern.compile(expression).matcher(text);
        while (matcher.find()) {
            String before = text.substring(Math.max(0, matcher.start() - 10), matcher.start());
            if (!before.matches("(?s).*(不要|不用|无需|不想听|不需要|别|跳过)(?:再|重新)?(?:听|讲|按|照)?\\s*$")) return true;
        }
        return false;
    }
    private static LocalDate date(java.util.regex.Matcher match) {
        try { return LocalDate.of(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)), Integer.parseInt(match.group(3))); }
        catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("请求中的日期无效，请使用 YYYY-MM-DD", invalid); }
    }
    private static int chineseNumber(String value) {
        if (value.contains("百")) return 100;
        int tens = value.indexOf('十');
        if (tens >= 0) return (tens == 0 ? 1 : digit(value.charAt(0))) * 10 + (tens + 1 == value.length() ? 0 : digit(value.charAt(tens + 1)));
        return digit(value.charAt(0));
    }
    private static int digit(char value) { return switch (value) { case '一' -> 1; case '二', '两' -> 2; case '三' -> 3; case '四' -> 4; case '五' -> 5; case '六' -> 6; case '七' -> 7; case '八' -> 8; case '九' -> 9; default -> 0; }; }
}
