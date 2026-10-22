package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.agent.audio.AudioAgent.CoursePlan;
import com.bedouyu.audiorag.agent.audio.AudioAgent.LessonDraft;
import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.model.entity.*;
import com.bedouyu.audiorag.repository.*;
import com.bedouyu.audiorag.service.ContinuousAudioService.QueueCommand;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Small transactional state machine. Every mutation locks queue -> segment;
 * slow model/TTS/file operations must remain outside these transactions.
 */
@Service
@Transactional
public class ContinuousAudioStore {
    private static final Duration PREPARATION_LEASE = Duration.ofMinutes(10);
    private final Duration synthesisLease;
    private final ContinuousAudioQueueRepository queues;
    private final ContinuousAudioSegmentRepository segments;
    private final LearningProjectRepository projects;
    private final AudioRagRepository records;
    private final LearningPlanService learning;
    private final ObjectMapper json;

    public ContinuousAudioStore(ContinuousAudioQueueRepository queues, ContinuousAudioSegmentRepository segments,
            LearningProjectRepository projects, AudioRagRepository records, LearningPlanService learning, ObjectMapper json) {
        this(queues, segments, projects, records, learning, json, 300);
    }

    @Autowired
    public ContinuousAudioStore(ContinuousAudioQueueRepository queues, ContinuousAudioSegmentRepository segments,
            LearningProjectRepository projects, AudioRagRepository records, LearningPlanService learning, ObjectMapper json,
            @Value("${audiorag.speech.synthesis-lease-seconds:300}") int synthesisLeaseSeconds) {
        if (synthesisLeaseSeconds <= 0) throw new IllegalArgumentException("语音合成租约秒数必须大于 0");
        this.synthesisLease = Duration.ofSeconds(synthesisLeaseSeconds);
        this.queues = queues; this.segments = segments; this.projects = projects;
        this.records = records; this.learning = learning; this.json = json;
    }

    /** The project lock serializes identical requests across application instances. */
    public Creation reserve(long userId, QueueCommand command) {
        requireValid(command);
        var project = projects.findLockedById(command.projectId())
                .orElseThrow(() -> notFound());
        if (project.getUserId() != userId) throw notFound();
        String requestId = requestId(command.requestId());
        String hash = commandHash(command);
        if (requestId != null) {
            var existing = queues.findByUserIdAndProjectIdAndRequestId(userId, command.projectId(), requestId);
            if (existing.isPresent()) {
                if (!hash.equals(existing.get().getRequestHash())) throw conflict("同一 request_id 不能用于不同课程参数");
                // Even failed or uncertain requests are only read back, never implicitly rerun.
                return new Creation(existing.get().getId(), null, false);
            }
        }
        String token = UUID.randomUUID().toString();
        String id = UUID.randomUUID().toString();
        queues.saveAndFlush(new ContinuousAudioQueue(id, userId, command.projectId(), requestId, hash,
                json.writeValueAsString(command), token, Instant.now().plus(PREPARATION_LEASE)));
        return new Creation(id, token, true);
    }

    public void prepared(long userId, Creation creation, CoursePlan course, List<String> scripts) {
        var queue = required(userId, creation.queueId());
        reconcile(queue);
        requireClaim(queue, "PREPARING", creation.token());
        if (course.title() == null || course.title().isBlank() || course.title().length() > 580
                || scripts.isEmpty() || scripts.size() > 200 || scripts.stream().anyMatch(s -> s == null || s.isBlank()))
            throw new IllegalArgumentException("课程标题或分段内容无效");
        for (int index = 0; index < scripts.size(); index++) {
            String script = scripts.get(index);
            segments.save(new ContinuousAudioSegment(queue.getId(), index + 1, script,
                    Hashing.sha256(script), UUID.randomUUID().toString()));
        }
        queue.prepared(course.title(), json.writeValueAsString(course.citations()), course.knowledgePointId());
    }

    public void preparationFailed(long userId, Creation creation, String error) {
        var queue = required(userId, creation.queueId());
        if ("PREPARING".equals(queue.getStatus()) && Objects.equals(queue.getLeaseToken(), creation.token()))
            queue.fail("FAILED", error);
    }

    public Optional<String> claimGeneration(long userId, String queueId) {
        var queue = required(userId, queueId);
        reconcile(queue);
        if ("GENERATING".equals(queue.getStatus())) return Optional.empty();
        if (!"READY".equals(queue.getStatus()) && !"COMPLETE".equals(queue.getStatus()))
            throw conflict("队列状态为 " + queue.getStatus() + "，不能自动继续；结果未知的片段必须先人工核验或重置");
        if (segments.findByQueueIdOrderBySegmentNumberAsc(queueId).stream().noneMatch(s -> "PENDING".equals(s.getStatus())))
            return Optional.empty();
        String token = UUID.randomUUID().toString();
        queue.claim(token, Instant.now().plus(synthesisLease));
        return Optional.of(token);
    }

    public Optional<Claim> claimNext(long userId, String queueId, String token) {
        var queue = required(userId, queueId);
        reconcile(queue);
        requireClaim(queue, "GENERATING", token);
        var values = segments.findByQueueIdOrderBySegmentNumberAsc(queueId);
        if (values.stream().anyMatch(s -> "SYNTHESIZING".equals(s.getStatus()) || "NEEDS_REVIEW".equals(s.getStatus())))
            throw conflict("前一合成结果尚未确认，不能领取后续片段");
        var next = values.stream().filter(s -> "PENDING".equals(s.getStatus())).findFirst();
        if (next.isEmpty()) return Optional.empty();
        Instant until = Instant.now().plus(synthesisLease);
        queue.renew(until);
        next.get().claim(token, until);
        return Optional.of(claim(queue, next.get()));
    }

    /** Course record, knowledge progress, and segment READY commit together. */
    public void complete(long userId, Claim claim, String audioUrl) {
        var queue = required(userId, claim.queueId());
        reconcile(queue);
        var segment = segment(queue, claim.segmentNumber());
        requireBound(segment, claim.scriptHash(), claim.audioId());
        if ("READY".equals(segment.getStatus())) return;
        requireClaim(queue, "GENERATING", claim.token());
        if (!"SYNTHESIZING".equals(segment.getStatus()) || !Objects.equals(segment.getLeaseToken(), claim.token()))
            throw conflict("片段领取已失效，保留已有音频等待人工核验");
        finishSegment(queue, segment, audioUrl);
        queue.renew(Instant.now().plus(synthesisLease));
    }

    public void uncertain(long userId, Claim claim, String error) {
        var queue = required(userId, claim.queueId());
        var segment = segment(queue, claim.segmentNumber());
        if ("SYNTHESIZING".equals(segment.getStatus()) && Objects.equals(segment.getLeaseToken(), claim.token())
                && segment.getAudioId().equals(claim.audioId()) && segment.getScriptHash().equals(claim.scriptHash())) {
            segment.uncertain(error);
            if (Objects.equals(queue.getLeaseToken(), claim.token())) queue.fail("NEEDS_REVIEW", error);
        }
    }

    public void releaseGeneration(long userId, String queueId, String token) {
        var queue = required(userId, queueId);
        if (!Objects.equals(queue.getLeaseToken(), token) || !"GENERATING".equals(queue.getStatus())) return;
        var values = segments.findByQueueIdOrderBySegmentNumberAsc(queueId);
        if (values.stream().anyMatch(s -> "SYNTHESIZING".equals(s.getStatus()))) {
            values.stream().filter(s -> "SYNTHESIZING".equals(s.getStatus())).forEach(s -> s.uncertain("工作线程结束，合成结果待核验"));
            queue.fail("NEEDS_REVIEW", "工作线程结束，合成结果待核验");
        } else queue.release(values.stream().allMatch(s -> "READY".equals(s.getStatus())) ? "COMPLETE" : "READY");
    }

    /** Validate ownership and bindings before the caller accesses any WAV file. */
    public Claim recoveryCandidate(long userId, String queueId, int number, boolean confirmed, String hash, String audioId) {
        if (!confirmed) throw new ApiException(HttpStatus.BAD_REQUEST, "恢复音频必须由用户明确确认");
        var queue = required(userId, queueId);
        reconcile(queue);
        var segment = segment(queue, number);
        requireBound(segment, hash, audioId);
        if (!"NEEDS_REVIEW".equals(segment.getStatus()) && !"READY".equals(segment.getStatus()))
            throw conflict("只有待核验片段可以恢复；正在运行的合成不能被人工接管");
        if ("GENERATING".equals(queue.getStatus()) || "CANCELLED".equals(queue.getStatus())) throw conflict("队列正在处理或已取消");
        return claim(queue, segment);
    }

    public Map<String, Object> recovered(long userId, String queueId, int number, String hash, String audioId) {
        var queue = required(userId, queueId);
        reconcile(queue);
        var segment = segment(queue, number);
        requireBound(segment, hash, audioId);
        if ("READY".equals(segment.getStatus())) return snapshot(queue);
        if (!"NEEDS_REVIEW".equals(segment.getStatus()) || "GENERATING".equals(queue.getStatus()) || "CANCELLED".equals(queue.getStatus()))
            throw conflict("恢复检查期间状态已改变，请刷新后重试");
        audit(segment, Map.of("action", "RECOVER_EXISTING_WAV", "user_id", userId, "confirmed", true,
                "script_hash", hash, "audio_id", audioId, "time", Instant.now().toString()));
        finishSegment(queue, segment, "/media/" + audioId + ".wav");
        refreshQueueStatus(queue);
        return snapshot(queue);
    }

    /** Reset is not synthesis: a separate refill is required to invoke TTS again. */
    public Map<String, Object> reset(long userId, String queueId, int number, boolean confirmed,
            String hash, String audioId, String reason) {
        if (!confirmed) throw new ApiException(HttpStatus.BAD_REQUEST, "重置合成必须由用户明确确认");
        if (reason == null || reason.strip().length() < 10 || reason.strip().length() > 500)
            throw new ApiException(HttpStatus.BAD_REQUEST, "重置理由需为 10 至 500 个字符");
        var queue = required(userId, queueId);
        reconcile(queue);
        var segment = segment(queue, number);
        requireBound(segment, hash, audioId);
        if (!"NEEDS_REVIEW".equals(segment.getStatus()) || "GENERATING".equals(queue.getStatus())
                || "CANCELLED".equals(queue.getStatus())) throw conflict("当前状态不允许重置合成");
        String nextAudioId = UUID.randomUUID().toString();
        audit(segment, Map.of("action", "RESET_SYNTHESIS", "user_id", userId, "confirmed", true,
                "script_hash", hash, "previous_audio_id", audioId, "new_audio_id", nextAudioId,
                "reason", reason.strip(), "time", Instant.now().toString()));
        segment.reset(nextAudioId);
        refreshQueueStatus(queue);
        return snapshot(queue);
    }

    /**
     * 换音色：讲稿保持不变，只为每条已完成的片段分配新的音频编号并回到 PENDING，
     * 由既有生成循环用新音色重念。返回需要延迟清理的旧音频编号。
     */
    public Map<String, Object> revoice(long userId, String queueId, String voice, String commandJson) {
        var queue = required(userId, queueId);
        reconcile(queue);
        if ("GENERATING".equals(queue.getStatus()) || "PREPARING".equals(queue.getStatus()))
            throw conflict("正在生成内容，请等这批片段完成后再换音色");
        if ("CANCELLED".equals(queue.getStatus())) throw conflict("已取消的任务不能换音色");
        var values = segments.findByQueueIdOrderBySegmentNumberAsc(queueId);
        if (values.stream().anyMatch(s -> "SYNTHESIZING".equals(s.getStatus()) || "NEEDS_REVIEW".equals(s.getStatus())))
            throw conflict("有片段仍在合成或等待核验，请先处理后再换音色");
        var ready = values.stream().filter(s -> "READY".equals(s.getStatus())).toList();
        if (ready.isEmpty()) throw conflict("还没有已完成的音频，先等第一段生成完成");
        List<String> retired = new ArrayList<>();
        for (var segment : ready) {
            String nextAudioId = UUID.randomUUID().toString();
            audit(segment, Map.of("action", "REVOICE", "user_id", userId, "voice", voice,
                    "previous_audio_id", segment.getAudioId(), "new_audio_id", nextAudioId,
                    "time", Instant.now().toString()));
            // 换音色是同一个条目的重制：删掉旧记录，重新合成后再写回一条，避免历史里出现重复条目。
            if (segment.getRecordId() != null) records.deleteProjectRecord(segment.getRecordId());
            retired.add(segment.getAudioId());
            segment.reset(nextAudioId);
        }
        queue.revoice(commandJson);
        queue.clearError();
        refreshQueueStatus(queue);
        return Map.of("segments", ready.size(), "retired", List.copyOf(retired));
    }

    public Optional<Map<String, Object>> statusIfPresent(long userId, String queueId) {
        var current = queues.findById(queueId).orElse(null);
        if (current == null) return Optional.empty();
        if (current.getUserId() != userId || !projects.existsByIdAndUserId(current.getProjectId(), userId)) throw notFound();
        // 轮询是纯读：租约仍在有效期内时没有需要纠正的状态，不取行锁，避免与生成任务争同一条队列行。
        if (current.getLeaseUntil() != null && current.getLeaseUntil().isAfter(Instant.now())) {
            return Optional.of(snapshot(current));
        }
        var locked = queues.findLockedById(queueId).orElseThrow(ContinuousAudioStore::notFound);
        reconcile(locked);
        return Optional.of(snapshot(locked));
    }

    /** 归属校验的廉价版本：不渲染快照，也不纠正租约状态。 */
    @Transactional(readOnly = true)
    public void requireOwned(long userId, String queueId) {
        var queue = queues.findById(queueId).orElseThrow(ContinuousAudioStore::notFound);
        if (queue.getUserId() != userId || !projects.existsByIdAndUserId(queue.getProjectId(), userId)) throw notFound();
    }

    /**
     * 保存收听进度前的校验：只取目标片段与片段总数，不再为一次 5 秒一次的写入重建整份队列快照。
     */
    @Transactional(readOnly = true)
    public long requirePlaybackTarget(long userId, String queueId, int index, boolean completed) {
        var queue = queues.findById(queueId).orElseThrow(ContinuousAudioStore::notFound);
        if (queue.getUserId() != userId || !projects.existsByIdAndUserId(queue.getProjectId(), userId)) throw notFound();
        var target = segments.findByQueueIdAndSegmentNumber(queueId, index + 1).orElse(null);
        if (target == null || !"READY".equals(target.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "该音频片段尚不可播放");
        }
        if (completed) {
            long total = segments.countByQueueId(queueId);
            if (index != total - 1 || !"COMPLETE".equals(queue.getStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "只有最后一个音频片段可以记为听完");
            }
        }
        return queue.getProjectId();
    }

    public Map<String, Object> status(long userId, String queueId) {
        return statusIfPresent(userId, queueId).orElseThrow(ContinuousAudioStore::notFound);
    }

    public QueueCommand commandForUser(long userId, String queueId) { return command(required(userId, queueId)); }

    public Optional<String> latestQueue(long userId, long projectId) {
        if (!projects.existsByIdAndUserId(projectId, userId)) throw notFound();
        return queues.findFirstByUserIdAndProjectIdOrderByUpdatedAtDescIdDesc(userId, projectId)
                .map(ContinuousAudioQueue::getId);
    }

    public Optional<String> latestQueueForUser(long userId) {
        return queues.findByUserIdOrderByUpdatedAtDesc(userId, org.springframework.data.domain.PageRequest.of(0, 50))
                .stream().filter(q -> projects.existsByIdAndUserId(q.getProjectId(), userId))
                .findFirst().map(ContinuousAudioQueue::getId);
    }

    public String scriptForUser(long userId, String queueId) {
        var queue = required(userId, queueId);
        var values = segments.findByQueueIdOrderBySegmentNumberAsc(queueId);
        if (values.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "上一段内容仍在准备，请等正文就绪后再修改或接续");
        String separator = Boolean.TRUE.equals(command(queue).context().get("exact_source")) ? "" : "\n\n";
        return values.stream().map(ContinuousAudioSegment::getScript).collect(java.util.stream.Collectors.joining(separator));
    }

    public Optional<String> naturalRequest(long userId, String requestId) {
        return queues.findByUserIdAndRequestId(userId, requestId).stream()
                .filter(q -> "natural".equals(command(q).context().get("entrypoint")))
                .findFirst().map(ContinuousAudioQueue::getId);
    }

    public List<Map<String, Object>> history(long userId, Long projectId) {
        if (projectId != null && !projects.existsByIdAndUserId(projectId, userId)) throw notFound();
        var page = org.springframework.data.domain.PageRequest.of(0, 50);
        var found = projectId == null ? queues.findByUserIdOrderByUpdatedAtDesc(userId, page)
                : queues.findByUserIdAndProjectIdOrderByUpdatedAtDesc(userId, projectId, page);
        return found
                .stream().filter(q -> (projectId == null || projectId == q.getProjectId())
                        && projects.existsByIdAndUserId(q.getProjectId(), userId))
                .map(this::snapshot).toList();
    }

    @Transactional(readOnly = true)
    public boolean ownsProject(long userId, long projectId) { return projects.existsByIdAndUserId(projectId, userId); }

    @Transactional(readOnly = true)
    public boolean ready() { queues.count(); return true; }

    public void cancelProject(long userId, long projectId) {
        if (!projects.existsByIdAndUserId(projectId, userId)) throw notFound();
        for (var item : queues.findByUserIdAndProjectId(userId, projectId)) {
            var queue = required(userId, item.getId());
            for (var segment : segments.findByQueueIdOrderBySegmentNumberAsc(queue.getId()))
                if ("SYNTHESIZING".equals(segment.getStatus())) segment.uncertain("项目已取消，外部合成结果不自动处理");
            queue.release("CANCELLED");
        }
    }

    private void finishSegment(ContinuousAudioQueue queue, ContinuousAudioSegment segment, String url) {
        String expected = "/media/" + segment.getAudioId() + ".wav";
        if (!expected.equals(url)) throw conflict("音频返回地址与预留编号不匹配");
        long recordId = records.addProjectRecord(queue.getProjectId(), "lesson", title(queue, segment), segment.getScript(),
                Map.of("lesson_id", segment.getAudioId(), "audio_url", url, "continuous", !"SINGLE".equals(command(queue).mode()),
                        "knowledge_point_id", queue.getKnowledgePointId(), "citations", citations(queue).size(),
                        "queue_id", queue.getId(), "segment_number", segment.getSegmentNumber(), "script_hash", segment.getScriptHash()));
        segment.ready(url, recordId);
        // One ready fragment must not mark an unfinished whole course as covered.
        if (queue.getKnowledgePointId() > 0 && segments.findByQueueIdOrderBySegmentNumberAsc(queue.getId()).stream().allMatch(s -> "READY".equals(s.getStatus())))
            learning.complete(queue.getKnowledgePointId());
    }

    private void refreshQueueStatus(ContinuousAudioQueue queue) {
        var values = segments.findByQueueIdOrderBySegmentNumberAsc(queue.getId());
        queue.clearError();
        queue.release(values.stream().anyMatch(s -> "NEEDS_REVIEW".equals(s.getStatus())) ? "NEEDS_REVIEW"
                : values.stream().allMatch(s -> "READY".equals(s.getStatus())) ? "COMPLETE" : "READY");
    }

    /** Expiration is uncertainty, not a retry signal. Never take another instance's live lease. */
    private void reconcile(ContinuousAudioQueue queue) {
        Instant now = Instant.now();
        if (queue.getLeaseUntil() == null || queue.getLeaseUntil().isAfter(now)) return;
        if ("PREPARING".equals(queue.getStatus())) {
            queue.fail("NEEDS_REVIEW", "课程准备租约过期；模型/知识点预留结果未知，不自动重放");
        } else if ("GENERATING".equals(queue.getStatus())) {
            boolean uncertain = false;
            for (var value : segments.findByQueueIdOrderBySegmentNumberAsc(queue.getId()))
                if ("SYNTHESIZING".equals(value.getStatus())) {
                    value.uncertain("合成租约过期；保留预留音频编号等待人工核验"); uncertain = true;
                }
            if (uncertain) queue.fail("NEEDS_REVIEW", "合成结果未知，禁止自动重试");
            else refreshQueueStatus(queue);
        }
    }

    private ContinuousAudioQueue required(long userId, String id) {
        var queue = queues.findLockedById(id).orElseThrow(ContinuousAudioStore::notFound);
        if (queue.getUserId() != userId || !projects.existsByIdAndUserId(queue.getProjectId(), userId)) throw notFound();
        return queue;
    }
    private ContinuousAudioSegment segment(ContinuousAudioQueue queue, int number) {
        return segments.findByQueueIdAndSegmentNumber(queue.getId(), number).orElseThrow(ContinuousAudioStore::notFound);
    }
    private void requireClaim(ContinuousAudioQueue queue, String status, String token) {
        if (token == null || !status.equals(queue.getStatus()) || !token.equals(queue.getLeaseToken()))
            throw conflict("队列领取已失效，不能继续执行外部副作用");
    }
    private void requireBound(ContinuousAudioSegment segment, String hash, String audioId) {
        if (hash == null || !hash.matches("[0-9a-f]{64}") || !segment.getScriptHash().equals(hash)
                || audioId == null || !segment.getAudioId().equals(audioId)) throw conflict("稿件版本或音频尝试编号已改变，请刷新");
    }
    private Claim claim(ContinuousAudioQueue queue, ContinuousAudioSegment segment) {
        var command = command(queue);
        return new Claim(queue.getId(), segment.getSegmentNumber(), segment.getScriptHash(), segment.getAudioId(),
                queue.getLeaseToken(), segment.getStatus(), command,
                new LessonDraft(segment.getAudioId(), title(queue, segment), segment.getScript(), citations(queue),
                        command.segmentMinutes(), queue.getKnowledgePointId()));
    }
    private String title(ContinuousAudioQueue queue, ContinuousAudioSegment segment) {
        if ("SINGLE".equals(command(queue).mode())) return queue.getCourseTitle();
        return queue.getCourseTitle() + " · 第" + segment.getSegmentNumber() + "节";
    }
    private QueueCommand command(ContinuousAudioQueue queue) { return json.readValue(queue.getCommandJson(), QueueCommand.class); }
    private List<Map<String, Object>> citations(ContinuousAudioQueue queue) {
        return queue.getCitationsJson() == null ? List.of() : json.readValue(queue.getCitationsJson(), new TypeReference<List<Map<String, Object>>>() {});
    }
    private void audit(ContinuousAudioSegment segment, Map<String, Object> entry) {
        List<Map<String, Object>> values = new ArrayList<>(json.readValue(segment.getAuditJson(), new TypeReference<List<Map<String, Object>>>() {}));
        values.add(entry); segment.audit(json.writeValueAsString(values));
    }
    private Map<String, Object> snapshot(ContinuousAudioQueue queue) {
        // Flush versions before rendering so Redis's version cannot represent pre-commit state.
        segments.flush(); queues.flush();
        var command = command(queue);
        var values = segments.findByQueueIdOrderBySegmentNumberAsc(queue.getId());
        List<Map<String, Object>> lessons = new ArrayList<>();
        List<Map<String, Object>> states = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (queue.getLastError() != null) errors.add(queue.getLastError());
        for (var segment : values) {
            if ("READY".equals(segment.getStatus())) {
                var lesson = new LinkedHashMap<>(claim(queue, segment).draft().response(segment.getAudioUrl()));
                lesson.put("segment_number", segment.getSegmentNumber()); lessons.add(lesson);
            }
            var state = new LinkedHashMap<String, Object>();
            state.put("segment_number", segment.getSegmentNumber()); state.put("status", segment.getStatus());
            state.put("script_hash", segment.getScriptHash()); state.put("audio_id", segment.getAudioId());
            state.put("audio_url", segment.getAudioUrl() == null ? "" : segment.getAudioUrl()); state.put("version", segment.getVersion());
            state.put("audit", json.readValue(segment.getAuditJson(), List.class));
            state.put("error", segment.getLastError() == null ? "" : segment.getLastError()); states.add(state);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queue_id", queue.getId()); result.put("status", queue.getStatus()); result.put("version", queue.getVersion());
        result.put("lessons", lessons); result.put("segments", states); result.put("errors", errors);
        result.put("generating", "GENERATING".equals(queue.getStatus()) ? command.prefetchSize() : 0);
        result.put("total_segments", values.size()); result.put("storage", "mysql-durable");
        result.put("prefetch_size", command.prefetchSize());
        result.put("mode", command.mode());
        // 记录实际音色：换音色后前端与历史都能看到"这条是用什么声音念的"。
        result.put("voice", command.voice() == null ? "" : command.voice());
        result.put("project_id", queue.getProjectId());
        result.put("title", queue.getCourseTitle() == null ? command.topic() : queue.getCourseTitle());
        result.put("instruction", command.context().getOrDefault("request_instruction", command.instruction() == null ? command.topic() : command.instruction()));
        result.put("citations", citations(queue));
        result.put("context", command.context());
        result.put("updated_at", queue.getUpdatedAt().toString());
        result.put("lease_until", queue.getLeaseUntil() == null ? "" : queue.getLeaseUntil().toString());
        return result;
    }
    private String commandHash(QueueCommand command) {
        Map<String, Object> fields = new TreeMap<>();
        fields.put("topic", command.topic()); fields.put("project_id", command.projectId()); fields.put("course_minutes", command.courseMinutes());
        fields.put("segment_minutes", command.segmentMinutes()); fields.put("audience", command.audience());
        fields.put("voice", command.voice()); fields.put("prefetch_size", command.prefetchSize());
        fields.put("mode", command.mode());
        // Do not add absent fields to legacy hashes: existing requests remain replayable.
        if (command.instruction() != null) fields.put("instruction", command.instruction());
        if (command.sourceText() != null) fields.put("source_text_hash", Hashing.sha256(command.sourceText()));
        if (command.sourceTitle() != null) fields.put("source_title", command.sourceTitle());
        if (!command.context().isEmpty()) fields.put("context", command.context());
        return Hashing.sha256(json.writeValueAsString(fields));
    }
    private static void requireValid(QueueCommand command) {
        if (command == null || command.topic() == null || command.topic().isBlank() || command.topic().length() > 500
                || command.courseMinutes() < 1 || command.courseMinutes() > 120 || command.segmentMinutes() < 1
                || command.segmentMinutes() > 30 || command.prefetchSize() < 1 || command.prefetchSize() > 10
                || (command.instruction() != null && command.instruction().length() > 30000)
                || (command.sourceText() != null && command.sourceText().length() > 50000)
                || !Set.of("CONTINUOUS", "SINGLE").contains(command.mode()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "课程参数超出支持范围");
    }
    private static String requestId(String input) {
        if (input == null || input.isBlank()) return null;
        try {
            String normalized = UUID.fromString(input).toString();
            if (!normalized.equals(input.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "request_id 必须是标准 UUID"); }
    }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "连续播放队列不存在或项目无权限"); }
    private static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    public record Creation(String queueId, String token, boolean created) { }
    public record Claim(String queueId, int segmentNumber, String scriptHash, String audioId, String token,
                        String status, QueueCommand command, LessonDraft draft) { }
}
