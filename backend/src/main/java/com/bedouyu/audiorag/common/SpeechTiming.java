package com.bedouyu.audiorag.common;

/**
 * 语音时长的唯一换算口径。
 *
 * <p>旧实现把「每分钟 260 个字符」套在所有语言上：中文大致成立，英文却相差约三倍，
 * 于是英文讲稿的估算时长、分段长度和朗读预算全都会算错。这里改为按文本自身的语言构成
 * 测量密度——中文按非空白字符，英文按词——再反推每分钟字符数，供分段与校验共用。
 */
public final class SpeechTiming {
    /**
     * 中文正常语速，非空白字符/分钟。
     *
     * <p>实测值：用本机已合成的 12 段真实 WAV 与其讲稿反算，汇总 193.0、逐段均值 192.4、
     * 长段落集中在 186—200（见 tools/measure-speech-rate.py）。旧的 260 只是先验猜测，
     * 会让"3 分钟"实际播到约 4 分钟。取 195 略高于实测，避免把正文压得过短。
     */
    public static final double CJK_CHARACTERS_PER_MINUTE = 195.0;
    /** 英文正常语速，词/分钟。 */
    public static final double LATIN_WORDS_PER_MINUTE = 150.0;

    private SpeechTiming() {
    }

    public static int nonWhitespaceCharacters(String text) {
        if (text == null) return 0;
        return text.replaceAll("\\s+", "").length();
    }

    public static int cjkCharacters(String text) {
        if (text == null) return 0;
        return (int) text.codePoints()
                .filter(value -> Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN)
                .count();
    }

    public static int latinWords(String text) {
        if (text == null) return 0;
        int words = 0;
        boolean inside = false;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            boolean letter = value < 128 && Character.isLetterOrDigit(value) || value == '\'';
            if (letter && !inside) words++;
            inside = letter;
        }
        return words;
    }

    /**
     * 按文本自身的中英构成换算每分钟字符数。纯中文≈260，纯英文≈850（150 词），中英混排取加权。
     * 没有任何可读内容时退回中文口径。
     */
    public static double charactersPerMinute(String text) {
        double minutes = cjkCharacters(text) / CJK_CHARACTERS_PER_MINUTE
                + latinWords(text) / LATIN_WORDS_PER_MINUTE;
        int characters = nonWhitespaceCharacters(text);
        if (minutes <= 0 || characters <= 0) return CJK_CHARACTERS_PER_MINUTE;
        return characters / minutes;
    }

    public static double estimatedSeconds(String text) {
        double minutes = cjkCharacters(text) / CJK_CHARACTERS_PER_MINUTE
                + latinWords(text) / LATIN_WORDS_PER_MINUTE;
        return minutes * 60.0;
    }

    /** 给定目标分钟数与参考文本的语言构成，换算应该写多少字符。 */
    public static long targetCharacters(String referenceText, int minutes) {
        if (minutes <= 0) return 0;
        return Math.round(minutes * charactersPerMinute(referenceText));
    }
}
