package com.bedouyu.audiorag.rag;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BertTokenizer {
    private static final int MAX_SEQUENCE_LENGTH = 512;
    private final Map<String, Integer> vocabulary;
    private final int clsId;
    private final int sepId;
    private final int unknownId;

    BertTokenizer(Path tokenizerFile, ObjectMapper objectMapper) throws IOException {
        JsonNode vocabularyNode = objectMapper.readTree(tokenizerFile.toFile()).path("model").path("vocab");
        if (!vocabularyNode.isObject()) {
            throw new IOException("tokenizer.json 中缺少 model.vocab");
        }
        vocabulary = new HashMap<>();
        vocabularyNode.properties().forEach(entry -> vocabulary.put(entry.getKey(), entry.getValue().asInt()));
        clsId = requiredToken("[CLS]");
        sepId = requiredToken("[SEP]");
        unknownId = requiredToken("[UNK]");
    }

    long[] encode(String text) {
        List<Integer> ids = new ArrayList<>();
        ids.add(clsId);
        for (String token : basicTokens(text)) {
            for (int id : wordPieces(token)) {
                if (ids.size() >= MAX_SEQUENCE_LENGTH - 1) {
                    break;
                }
                ids.add(id);
            }
            if (ids.size() >= MAX_SEQUENCE_LENGTH - 1) {
                break;
            }
        }
        ids.add(sepId);
        long[] encoded = new long[ids.size()];
        for (int index = 0; index < ids.size(); index++) {
            encoded[index] = ids.get(index);
        }
        return encoded;
    }

    /** Count the entire text, without the encoder's 510-token truncation or CLS/SEP. */
    int contentTokenCount(String text) {
        int count = 0;
        for (String token : basicTokens(text)) count += wordPieces(token).length;
        return count;
    }

    private List<String> basicTokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String normalized = text == null ? "" : text.strip();
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                flush(current, tokens);
            } else if (isCjk(codePoint) || isPunctuation(codePoint)) {
                flush(current, tokens);
                tokens.add(new String(Character.toChars(codePoint)));
            } else {
                current.appendCodePoint(codePoint);
            }
        }
        flush(current, tokens);
        return tokens;
    }

    private int[] wordPieces(String token) {
        Integer exact = vocabulary.get(token);
        if (exact != null) {
            return new int[]{exact};
        }
        List<Integer> pieces = new ArrayList<>();
        int start = 0;
        while (start < token.length()) {
            int end = token.length();
            Integer match = null;
            int matchEnd = start;
            while (end > start) {
                String candidate = (start == 0 ? "" : "##") + token.substring(start, end);
                match = vocabulary.get(candidate);
                if (match == null) {
                    match = vocabulary.get(candidate.toLowerCase());
                }
                if (match != null) {
                    matchEnd = end;
                    break;
                }
                end--;
            }
            if (match == null) {
                return new int[]{unknownId};
            }
            pieces.add(match);
            start = matchEnd;
        }
        return pieces.stream().mapToInt(Integer::intValue).toArray();
    }

    private int requiredToken(String token) throws IOException {
        Integer id = vocabulary.get(token);
        if (id == null) {
            throw new IOException("分词器缺少特殊标记 " + token);
        }
        return id;
    }

    private static void flush(StringBuilder current, List<String> tokens) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x20000 && codePoint <= 0x2CEAF);
    }

    private static boolean isPunctuation(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }
}
