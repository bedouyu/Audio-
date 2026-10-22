package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.model.entity.KnowledgeIndexDocument;
import com.bedouyu.audiorag.rag.index.KnowledgeIndexStore;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Selects an unmodified, contiguous range of the canonical uploaded text for reading aloud. */
@Service
public class AudioSourceService {
    private static final int MAX_CHARACTERS = 50_000;
    private static final String NUMBER = "[0-9零〇一二两三四五六七八九十百千万]+";
    private static final Pattern CHAPTER_REQUEST = Pattern.compile(
            "第\\s*(" + NUMBER + ")\\s*([章回节卷])|(?i:chapter)\\s+([0-9]+)\\b");
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "(?m)^[\\t \\u3000]*(?:#{1,6}[\\t ]*)?(?:第\\s*(" + NUMBER
                    + ")\\s*([章回节卷])|(?i:chapter)\\s+([0-9]+)\\b)[^\\r\\n]*");
    private static final Pattern CONTINUE = Pattern.compile(
            "接着\\s*读|继续\\s*(?:朗)?读|继续\\s*朗读|下一章|下一回|下一节|下一卷|(?i:continue\\s+(?:reading|narrating)|next\\s+chapter)");
    private static final Pattern NEXT_CHAPTER = Pattern.compile("下一[章回节卷]|(?i:next\\s+chapter)");

    private final AudioRagRepository repository;
    private final KnowledgeIndexStore index;

    public AudioSourceService(AudioRagRepository repository, KnowledgeIndexStore index) {
        this.repository = repository;
        this.index = index;
    }

    /** The caller uses this only for explicit source reading, never for generating original content. */
    public SourceSelection resolve(long userId, long projectId, String instruction, Long sourceRecordId,
                                   Map<String, Object> previousContext, int targetMinutes) {
        if (!repository.ownsProject(userId, projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "项目不存在或无访问权限");
        }
        String request = instruction == null ? "" : instruction;
        Map<String, Object> previous = previousContext == null ? Map.of() : previousContext;
        boolean continuing = CONTINUE.matcher(request).find();
        List<KnowledgeIndexDocument> documents = index.documents(projectId);
        KnowledgeIndexDocument document = selectDocument(userId, projectId, request,
                sourceRecordId, previous, continuing, documents);
        String text = document.getText();
        if (text == null || text.isBlank()) throw badRequest("这份资料没有可朗读的正文，请上传包含正文的文件");
        List<Chapter> chapters = chapters(text);
        Chapter requestedChapter = requestedChapter(request, chapters);

        int start = 0;
        if (requestedChapter != null) {
            start = requestedChapter.start();
        } else if (continuing) {
            if (!document.getId().equals(previous.get("source_document_id"))) {
                throw badRequest("没有这份资料的朗读进度，请先指定起始章节或从头朗读");
            }
            if (!document.getTextVersion().equals(previous.get("source_version"))) {
                throw badRequest("原文已经更新，旧朗读位置不再适用，请指定章节或从头重新朗读");
            }
            start = offset(previous.get("source_end"), text.length());
            if (NEXT_CHAPTER.matcher(request).find()) {
                int previousEnd = start;
                start = chapters.stream().filter(chapter -> chapter.start() >= previousEnd)
                        .mapToInt(Chapter::start).findFirst().orElseThrow(() ->
                                badRequest("原文中没有下一章，请继续朗读当前章节的剩余内容或指定章节重新收听"));
            }
        }
        if (start >= text.length()) throw badRequest("这份资料已经朗读完毕，可以指定章节重新收听或选择其他资料");

        long budget = (long) (targetMinutes > 0 ? targetMinutes : 5) * 260L;
        int maximum = (int) Math.min(MAX_CHARACTERS, Math.max(1L, budget));
        int end = (int) Math.min(text.length(), (long) start + maximum);
        for (Chapter chapter : chapters) {
            if (chapter.start() > start) {
                end = Math.min(end, chapter.start());
                break;
            }
        }
        // Preserve supplementary characters without changing the canonical UTF-16 offsets.
        if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        if (end <= start) throw badRequest("朗读片段长度不足，请增加音频时长后重试");

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("source_document_id", document.getId());
        context.put("source_version", document.getTextVersion());
        context.put("source_start", start);
        context.put("source_end", end);
        context.put("source_total", text.length());
        context.put("source_title", document.getTitle());
        context.put("exact_source", true);
        if (sourceRecordId != null) context.put("source_record_id", sourceRecordId);
        return new SourceSelection(text.substring(start, end), document.getTitle(), Map.copyOf(context));
    }

    private KnowledgeIndexDocument selectDocument(long userId, long projectId, String request,
            Long sourceRecordId, Map<String, Object> previous, boolean continuing,
            List<KnowledgeIndexDocument> documents) {
        if (sourceRecordId != null) {
            Map<String, Object> record = repository.knowledgeRecord(userId, projectId, sourceRecordId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "指定资料不存在或不属于当前项目，请重新选择资料"));
            Object metadata = record.get("metadata");
            Object documentId = metadata instanceof Map<?, ?> values ? values.get("document_id") : null;
            if (!(documentId instanceof String id) || id.isBlank()) {
                throw badRequest("指定资料没有完整原文索引，请重新上传原文件后朗读");
            }
            return findDocument(documents, id);
        }
        String lower = request.toLowerCase(Locale.ROOT);
        List<KnowledgeIndexDocument> mentioned = documents.stream()
                .filter(document -> document.getTitle() != null && !document.getTitle().isBlank()
                        && lower.contains(document.getTitle().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparingInt((KnowledgeIndexDocument document) -> document.getTitle().length()).reversed())
                .toList();
        if (!mentioned.isEmpty()) {
            KnowledgeIndexDocument strongest = mentioned.get(0);
            String strongestTitle = strongest.getTitle().toLowerCase(Locale.ROOT);
            boolean anotherSource = mentioned.stream().skip(1).anyMatch(document ->
                    document.getTitle().equalsIgnoreCase(strongest.getTitle())
                            || !strongestTitle.contains(document.getTitle().toLowerCase(Locale.ROOT)));
            if (!anotherSource) return strongest;
            throw badRequest("指令匹配到多份资料，请通过资料编号选择一本要朗读的原文");
        }
        if (continuing && previous.get("source_document_id") instanceof String id) {
            return findDocument(documents, id);
        }
        if (documents.size() == 1) return documents.get(0);
        if (documents.isEmpty()) throw badRequest("请先上传要朗读的小说、文章或其他原文，再告诉我要读哪一章");
        String titles = documents.stream().limit(5).map(KnowledgeIndexDocument::getTitle)
                .reduce((first, next) -> first + "、" + next).orElse("");
        throw badRequest("当前有多份原文，请在指令中写明资料完整标题或选择资料：" + titles);
    }

    private KnowledgeIndexDocument findDocument(List<KnowledgeIndexDocument> documents, String id) {
        return documents.stream().filter(document -> id.equals(document.getId())).findFirst()
                .orElseThrow(() -> badRequest("找不到这份资料的完整原文，可能已删除或更新，请重新选择或上传资料"));
    }

    private static int offset(Object value, int total) {
        long parsed;
        try {
            if (value == null) throw new NumberFormatException();
            parsed = Long.parseLong(value.toString());
        } catch (NumberFormatException invalid) {
            throw badRequest("没有有效的朗读位置，请指定章节或从头朗读");
        }
        if (parsed < 0 || parsed > total) throw badRequest("保存的朗读位置已失效，请指定章节或从头朗读");
        return (int) parsed;
    }

    private static List<Chapter> chapters(String text) {
        List<Chapter> result = new ArrayList<>();
        var matcher = CHAPTER_HEADING.matcher(text);
        while (matcher.find()) {
            int number = number(matcher.group(1) == null ? matcher.group(3) : matcher.group(1));
            String kind = matcher.group(2) == null ? "章" : matcher.group(2);
            if (number >= 0) result.add(new Chapter(number, kind, matcher.start()));
        }
        return result;
    }

    private static Chapter requestedChapter(String request, List<Chapter> chapters) {
        var matcher = CHAPTER_REQUEST.matcher(request);
        if (!matcher.find()) return null;
        int number = number(matcher.group(1) == null ? matcher.group(3) : matcher.group(1));
        String kind = matcher.group(2) == null ? "章" : matcher.group(2);
        return chapters.stream().filter(chapter -> chapter.number() == number && chapter.kind().equals(kind))
                .findFirst().orElseThrow(() -> badRequest("原文中未找到指定章节，请核对章节编号或从头朗读"));
    }

    private static int number(String text) {
        try { return Integer.parseInt(text); }
        catch (NumberFormatException ignored) { }
        int total = 0, section = 0, digit = 0;
        for (char character : text.toCharArray()) {
            int unit = switch (character) { case '十' -> 10; case '百' -> 100; case '千' -> 1000; case '万' -> 10000; default -> 0; };
            if (unit == 10000) { total += (section + digit) * unit; section = 0; digit = 0; }
            else if (unit > 0) { section += Math.max(1, digit) * unit; digit = 0; }
            else {
                int next = switch (character) {
                    case '零', '〇' -> 0; case '一' -> 1; case '二', '两' -> 2; case '三' -> 3;
                    case '四' -> 4; case '五' -> 5; case '六' -> 6; case '七' -> 7;
                    case '八' -> 8; case '九' -> 9; default -> -1;
                };
                if (next < 0) return -1;
                digit = digit * 10 + next;
            }
        }
        return total + section + digit;
    }

    private static ApiException badRequest(String message) { return new ApiException(HttpStatus.BAD_REQUEST, message); }
    private record Chapter(int number, String kind, int start) { }
    public record SourceSelection(String text, String title, Map<String, Object> context) { }
}
