package com.bedouyu.audiorag.rag;

import java.util.ArrayList;
import java.util.List;

/** 仅用于回归比较的旧版 900/120 字符窗口，不注册为生产组件。 */
public class TextChunker {
    private static final int CHUNK_SIZE = 900;
    private static final int OVERLAP = 120;

    public List<String> split(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").trim();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int idealEnd = Math.min(normalized.length(), start + CHUNK_SIZE);
            int end = boundary(normalized, start, idealEnd);
            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end >= normalized.length()) {
                break;
            }
            start = Math.max(start + 1, end - OVERLAP);
        }
        return chunks;
    }

    private int boundary(String text, int start, int idealEnd) {
        if (idealEnd >= text.length()) {
            return text.length();
        }
        int floor = Math.max(start + CHUNK_SIZE / 2, idealEnd - 180);
        for (int index = idealEnd; index >= floor; index--) {
            char value = text.charAt(index - 1);
            if (value == '\n' || value == '。' || value == '！' || value == '？' || value == '.') {
                return index;
            }
        }
        return idealEnd;
    }
}
