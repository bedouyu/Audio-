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
            return new Accepted(projects.project(userId, receipt.get().getProjectId()), command,
                    new ContinuousAudioStore.Creation(receipt.get().getQueueId(), null, false), true);
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
        boolean contextual = request.previousQueueId() != null || progressQuestion || matches(instruction, "(?i)刚才|上一(?:段|集|章|期)|上次|接着|继续|续写|换成|改成|短一点|长一点|下一章|previous|continue|resume|shorter|longer");
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
        var command = new QueueCommand(referenceTopic, projectId, intent.durationMinutes(),
                Math.min(3, intent.durationMinutes()), null, voice, 2, id, "CONTINUOUS", effective, sourceText, sourceTitle, context);
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
    public record Accepted(Map<String, Object> project, QueueCommand command, ContinuousAudioStore.Creation creation, boolean resume) { }
}
