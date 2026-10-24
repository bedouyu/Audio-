package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.common.*;
import com.bedouyu.audiorag.model.dto.AudioInstructionRequest;
import com.bedouyu.audiorag.model.entity.AudioInstructionReceipt;
import com.bedouyu.audiorag.repository.*;
import com.bedouyu.audiorag.service.ContinuousAudioService.QueueCommand;
import com.bedouyu.audiorag.service.content.AudioIntent;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/** Short DB-only reservation; model, HTTP, and speech work happen after this transaction commits. */
@Service
public class NaturalAudioRequestStore {
    private final UserAccountRepository users;
    private final AudioRagRepository projects;
    private final ContinuousAudioStore queues;
    private final AudioSourceService sources;
    private final PlaybackProgressService playback;
    private final ObjectMapper json;
    private final AudioInstructionReceiptRepository receipts;
    public NaturalAudioRequestStore(UserAccountRepository users, AudioRagRepository projects, ContinuousAudioStore queues,
            AudioSourceService sources, PlaybackProgressService playback, ObjectMapper json, AudioInstructionReceiptRepository receipts) {
        this.users = users; this.projects = projects; this.queues = queues; this.sources = sources; this.playback = playback; this.json = json;
        this.receipts = receipts;
    }

    @Transactional
    public Accepted reserve(long userId, AudioInstructionRequest request) {
        String id = uuid(request.requestId()), instruction = request.instruction().strip();
        users.findLockedById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
        String fingerprint = fingerprint(request);
        var receipt = receipts.findByUserIdAndRequestId(userId, id);
        if (receipt.isPresent()) {
            if (!fingerprint.equals(receipt.get().getFingerprint()))
                throw new ApiException(HttpStatus.CONFLICT, "同一 request_id 不能用于不同的收听指令");
            var command = queues.commandForUser(userId, receipt.get().getQueueId());
            String revisionKind = receipt.get().getAction().startsWith("REVIEW_")
                    ? receipt.get().getAction().substring("REVIEW_".length()) : null;
            return new Accepted(projects.project(userId, receipt.get().getProjectId()), command,
                    new ContinuousAudioStore.Creation(receipt.get().getQueueId(), null, false), revisionKind == null, revisionKind);
        }
        var replay = queues.naturalRequest(userId, id);
        if (replay.isPresent()) {
            var previous = queues.commandForUser(userId, replay.get());
            if (!fingerprint.equals(previous.context().get("request_fingerprint")))
                throw new ApiException(HttpStatus.CONFLICT, "同一 request_id 不能用于不同的收听指令");
            return new Accepted(projects.project(userId, previous.projectId()), previous,
                    new ContinuousAudioStore.Creation(replay.get(), null, false), true);
        }

        Map<String, Object> project = request.projectId() == null ? null : projects.project(userId, request.projectId());
        if (project == null && request.sourceRecordId() != null) project = projects.projectForKnowledge(userId, request.sourceRecordId());
        boolean progressQuestion = matches(instruction, "(?i)^(?:现在|目前|当前|这件事|它|刚才).{0,12}(?:新进展|新消息|新的消息|更新)|^(?:any|what|are there).{0,20}new developments");
        boolean contextual = request.previousQueueId() != null || progressQuestion
                || matches(instruction, "(?i)刚才|上一(?:段|集|章|期)|上次|接着|继续|续写|换成|改成|短一点|长一点|下一章|previous|continue|resume|shorter|longer")
                || matches(instruction, "(?i)^(?:请|帮我|麻烦)?(?:修改|改写|重写|缩短|加长|删掉|删除|去掉|追加|再生成一些|多来|再来|rewrite\\b|revise\\b|extend\\b|append\\b)");
        String previousId = request.previousQueueId() == null ? null : uuid(request.previousQueueId());
        if (contextual && previousId == null) previousId = project == null ? queues.latestQueueForUser(userId).orElse(null)
                : queues.latestQueue(userId, ((Number) project.get("id")).longValue()).orElse(null);
        QueueCommand previous = previousId == null ? null : queues.commandForUser(userId, previousId);
        if (previous != null && project != null && previous.projectId() != ((Number) project.get("id")).longValue())
            throw new ApiException(HttpStatus.NOT_FOUND, "要接续的音频不属于当前内容空间");
        if (project == null && previous != null) project = projects.project(userId, previous.projectId());
        boolean plainResume = matches(instruction, "(?i)^(?:继续|接着|(?:接着|继续)(?:朗读|读|听|播放)(?:这本(?:书|小说)|这篇(?:文章)?|[《“][^》”]+[》”])?|resume|continue)[。！!\\s]*$");
        if (plainResume && previous != null && !playback.completed(userId, previousId)) {
            receipts.saveAndFlush(new AudioInstructionReceipt(userId, previous.projectId(), id, fingerprint, previousId));
            return new Accepted(project, previous, new ContinuousAudioStore.Creation(previousId, null, false), true);
        }
        if (plainResume && previous == null)
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "还没有可接续的音频，请先描述想听的内容或导入原文");
        if (previous == null && request.projectId() == null && request.sourceRecordId() == null
                && matches(instruction, "(?i)^(?:下一章|next chapter)[。！!\\s]*$"))
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "请先打开要接续的原文音频，再指定下一章");

        // 自然语言入口的改稿也先进入差异审核，不另建队列直接合成。
        String revisionKind = contextual && request.sourceRecordId() == null ? scriptRevisionKind(instruction) : null;
        if (revisionKind != null) {
            if (previous == null)
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "请先打开要修改的音频，再描述修改要求");
            receipts.saveAndFlush(new AudioInstructionReceipt(userId, previous.projectId(), id, fingerprint,
                    previousId, "REVIEW_" + revisionKind));
            return new Accepted(project, previous, new ContinuousAudioStore.Creation(previousId, null, false), false, revisionKind);
        }

        int fallback = previous == null ? 5 : previous.courseMinutes();
        if (matches(instruction, "(?i)短一点|缩短|shorter")) fallback = Math.max(1, fallback / 2);
        if (matches(instruction, "(?i)长一点|加长|longer")) fallback = Math.min(60, fallback * 2);
        String effective = instruction;
        if (previous != null && !matches(instruction, "(?i)中文|英语|英文|双语|chinese|english|bilingual")) {
            String language = String.valueOf(previous.context().getOrDefault("language", "zh"));
            if ("en".equals(language)) effective += "\n请保持使用英语。";
            if ("bilingual".equals(language)) effective += "\n请保持中英双语。";
        }
        if (plainResume && previous != null && Boolean.TRUE.equals(previous.context().get("exact_source")))
            effective += "\n接着朗读原文。";
        AudioIntent intent;
        try { intent = AudioIntent.resolve(effective, fallback); }
        catch (IllegalArgumentException invalid) { throw new ApiException(HttpStatus.BAD_REQUEST, invalid.getMessage()); }
        if (contextual && previous == null && !intent.exactSourceRequested())
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "请先打开要接续或修改的音频，再描述新的要求");
        if (project == null) project = projects.ensureAudioProject(userId, instruction);
        long projectId = ((Number) project.get("id")).longValue();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("entrypoint", "natural"); context.put("request_fingerprint", fingerprint);
        context.put("request_instruction", instruction); context.put("language", intent.language());
        if (previousId != null) context.put("previous_queue_id", previousId);
        String sourceText = null, sourceTitle = null;
        if (intent.exactSourceRequested() || request.sourceRecordId() != null) {
            var source = sources.resolve(userId, projectId, plainResume ? "接着读原文" : instruction, request.sourceRecordId(),
                    previous == null ? Map.of() : previous.context(), intent.durationMinutes());
            sourceText = source.text(); sourceTitle = source.title(); context.putAll(source.context());
            if (!intent.exactSourceRequested()) context.put("exact_source", false);
        }
        String voice = request.voice() == null || request.voice().isBlank() ? null : request.voice().strip();
        String referenceTopic = progressQuestion && previous != null
                ? String.valueOf(previous.context().getOrDefault("request_instruction", previous.topic())) : String.valueOf(project.get("topic"));
        if (referenceTopic.length() > 500) referenceTopic = referenceTopic.substring(0, 500);
        // 默认只合成第一段：讲稿先给用户看，确认后再按需生成后续片段。
        var command = new QueueCommand(referenceTopic, projectId, intent.durationMinutes(),
                Math.min(3, intent.durationMinutes()), null, voice, 1, id, "CONTINUOUS", effective, sourceText, sourceTitle, context);
        var creation = queues.reserve(userId, command);
        receipts.saveAndFlush(new AudioInstructionReceipt(userId, projectId, id, fingerprint, creation.queueId()));
        return new Accepted(project, command, creation, !creation.created());
    }

    private String fingerprint(AudioInstructionRequest request) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("instruction", request.instruction().strip()); fields.put("project_id", request.projectId());
        fields.put("source_record_id", request.sourceRecordId()); fields.put("voice", request.voice() == null ? "" : request.voice().strip());
        fields.put("previous_queue_id", request.previousQueueId());
        return Hashing.sha256(json.writeValueAsString(fields));
    }
    private static boolean matches(String text, String expression) { return Pattern.compile(expression).matcher(text).find(); }
    private static String uuid(String input) {
        try {
            if (input == null || !UUID.fromString(input).toString().equalsIgnoreCase(input)) throw new IllegalArgumentException();
            return input.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException invalid) { throw new ApiException(HttpStatus.BAD_REQUEST, "request_id 必须是标准 UUID"); }
    }
    private static String scriptRevisionKind(String instruction) {
        String command = instruction.strip().replaceFirst("^(?:(?:请你|请|帮我|麻烦你|麻烦|能不能|可以)\\s*)+", "");
        // 新主题里可能包含“修改个人信息”等词，不能因存在旧音频而覆盖它。
        if (matches(command, "(?i)^(?:给我)?(?:做(?:一|个|期)|制作|生成(?:一|个|篇)|写(?:一|个|篇)|创作|介绍|讲解|讲讲|讲一(?:个|段)|来一(?:个|段)|create\\b|write\\b|tell\\b|explain\\b)"))
            return null;
        boolean currentTarget = matches(command, "(?i)当前|现有|原来|原稿|上一版|刚才|这(?:份|篇|段|条|个)?(?:讲稿|音频|正文|稿子|内容)|(?:所有|这些|全部)(?:的)?(?:段落|内容|句子)|\\b(?:current|previous|this)\\s+(?:script|audio|draft)\\b");
        String append = "(?i)续写|追加|再生成一些|多来(?:几|十|点|一些)|再来(?:几|十|点|一些)|继续写|接着写|继续讲|接着讲|\\b(?:extend|append)\\b";
        if (matches(command, "(?i)^(?:按(?:照)?(?:这个|刚才|当前|原来).{0,20})?(?:续写|追加|再生成一些|多来|再来|继续写|接着写|继续讲|接着讲|extend\\b|append\\b)")
                || currentTarget && matches(command, append))
            return "EXTEND";
        String rewrite = "(?i)改成|换成.{0,12}(?:语|文|格式|风格)|修改|改写|重写|缩短|短一点|长一点|加长|删掉|删除|去掉|\\b(?:rewrite|revise|shorter|longer)\\b";
        if (matches(command, "(?i)^(?:改成|换成.{0,12}(?:语|文|格式|风格)|修改|改写|重写|缩短|短一点|长一点|加长|删掉|删除|去掉|rewrite\\b|revise\\b|shorter\\b|longer\\b)")
                || currentTarget && matches(command, rewrite))
            return "REWRITE";
        return null;
    }
    public record Accepted(Map<String, Object> project, QueueCommand command, ContinuousAudioStore.Creation creation,
                           boolean resume, String revisionKind) {
        public Accepted(Map<String, Object> project, QueueCommand command, ContinuousAudioStore.Creation creation, boolean resume) {
            this(project, command, creation, resume, null);
        }
    }
}
