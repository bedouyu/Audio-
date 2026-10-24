package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.agent.audio.AudioAgent.CourseCommand;
import com.bedouyu.audiorag.agent.audio.AudioAgent.CoursePlan;
import com.bedouyu.audiorag.agent.audio.AudioAgent.LessonCommand;
import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.common.SpeechTiming;
import com.bedouyu.audiorag.tools.audio.SpeechRateCalibration;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.tools.audio.WavVerifier;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Coordinates slow work, while the transactional store owns every state transition. */
@Service
public class ContinuousAudioService {
    private static final DefaultRedisScript<Long> CACHE_SNAPSHOT = new DefaultRedisScript<>("""
            local old = redis.call('GET', KEYS[1])
            if old then
                local ok, value = pcall(cjson.decode, old)
                if ok and tonumber(value.version or -1) > tonumber(ARGV[1]) then return 0 end
            end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', 604800)
            return 1
            """, Long.class);
    private final AudioAgent agent;
    private final SpeechService speech;
    private final ContinuousAudioStore store;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ExecutorService executor;
    /** 用真实合成结果校准语速；手工构造（测试）时保持默认实例，不依赖 Spring 注入。 */
    private SpeechRateCalibration calibration = new SpeechRateCalibration();
    /** 换音色后旧音频的延迟清理；手工构造（测试）时不注入也不影响主流程。 */
    private AudioRetention retention;
    /** 按意见改写讲稿；未注入（测试）时只能手工编辑。 */
    private ScriptReviser reviser;

    @Autowired(required = false)
    void setCalibration(SpeechRateCalibration value) { this.calibration = value; }

    @Autowired(required = false)
    void setRetention(AudioRetention value) { this.retention = value; }

    @Autowired(required = false)
    void setReviser(ScriptReviser value) { this.reviser = value; }

    @Autowired
    public ContinuousAudioService(AudioAgent agent, SpeechService speech, ContinuousAudioStore store,
            StringRedisTemplate redis, ObjectMapper json) {
        this(agent, speech, store, redis, json, new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy()));
    }

    /** A handoff queue has no waiting backlog: a lease is immediately executed or released on rejection. */
    ContinuousAudioService(AudioAgent agent, SpeechService speech, ContinuousAudioStore store,
            StringRedisTemplate redis, ObjectMapper json, ExecutorService executor) {
        this.agent = agent; this.speech = speech; this.store = store; this.redis = redis; this.json = json;
        this.executor = executor;
    }

    public Map<String, Object> start(long userId, QueueCommand command) {
        var creation = store.reserve(userId, command);
        if (!creation.created()) return status(userId, creation.queueId());
        try {
            var course = agent.createCourse(userId, new CourseCommand(command.topic(), command.projectId(),
                    command.courseMinutes(), command.audience()));
            store.prepared(userId, creation, course, splitCourse(course.script(), command.segmentMinutes()));
        } catch (RuntimeException error) {
            store.preparationFailed(userId, creation, error.getMessage());
            throw error;
        }
        generate(userId, creation.queueId(), command.prefetchSize());
        return status(userId, creation.queueId());
    }

    /** Single-lesson generation uses the same durable boundary instead of a legacy direct TTS call. */
    public Map<String, Object> startSingle(long userId, LessonCommand command, String requestId) {
        var queueCommand = new QueueCommand(command.topic(), command.projectId(), command.durationMinutes(),
                command.durationMinutes(), command.audience(), command.voice(), 1, requestId, "SINGLE");
        var creation = store.reserve(userId, queueCommand);
        if (!creation.created()) return status(userId, creation.queueId());
        try {
            var draft = agent.createLesson(userId, command);
            store.prepared(userId, creation, new CoursePlan(draft.title(), draft.script(), draft.citations(), draft.knowledgePointId()),
                    List.of(draft.script()));
        } catch (RuntimeException error) {
            store.preparationFailed(userId, creation, error.getMessage());
            throw error;
        }
        generate(userId, creation.queueId(), 1);
        return status(userId, creation.queueId());
    }

    /** Natural requests return immediately; preparation and synthesis share one bounded worker. */
    public Map<String, Object> startInstruction(long userId, QueueCommand command, ContinuousAudioStore.Creation creation) {
        if (!creation.created()) return status(userId, creation.queueId());
        try {
            executor.submit(() -> {
                try {
                    boolean exact = Boolean.TRUE.equals(command.context().get("exact_source"));
                    // Exact reading can discover language from the actual original at synthesis time.
                    if (!exact) speech.selectVoice(command.voice(), String.valueOf(command.context().getOrDefault("language", "zh")));
                    String previousScript = command.context().get("previous_queue_id") instanceof String previous
                            ? store.scriptForUser(userId, previous) : null;
                    if (command.sourceText() != null && !exact) previousScript =
                            "选定资料：" + command.sourceTitle() + "\n以下仅为该资料从 " + command.context().get("source_start")
                            + " 到 " + command.context().get("source_end") + " 的原文片段，全文长度 " + command.context().get("source_total")
                            + "。只能根据这一片段进行改写或解释，不得声称已概括未提供的全文。\n" + command.sourceText();
                    var content = agent.createCourse(userId, new CourseCommand(command.topic(), command.projectId(),
                            command.courseMinutes(), command.audience(), command.instruction(), exact ? command.sourceText() : null,
                            exact ? command.sourceTitle() : null, previousScript));
                    if (command.sourceText() == null && content.citations().isEmpty()
                            && command.context().get("previous_queue_id") instanceof String prior) {
                        var inherited = new ArrayList<Map<String, Object>>();
                        for (var citation : (List<?>) store.status(userId, prior).getOrDefault("citations", List.of())) {
                            if (citation instanceof Map<?, ?> values) {
                                var adapted = new LinkedHashMap<String, Object>();
                                values.forEach((key, value) -> adapted.put(String.valueOf(key), value));
                                adapted.put("adapted_from_queue", prior); adapted.put("reverified", false); inherited.add(adapted);
                            }
                        }
                        content = new CoursePlan(content.title(), content.script(), inherited, content.knowledgePointId());
                    }
                    if (command.sourceText() != null) {
                        var sources = new ArrayList<>(content.citations());
                        sources.add(Map.of("title", command.sourceTitle(), "source", "已导入原文",
                                "source_document_id", command.context().get("source_document_id"),
                                "source_start", command.context().get("source_start"), "source_end", command.context().get("source_end")));
                        content = new CoursePlan(content.title(), content.script(), sources, content.knowledgePointId());
                    }
                    store.prepared(userId, creation, content, !exact
                            ? splitCourse(content.script(), command.segmentMinutes()) : splitExact(content.script(), command.segmentMinutes()));
                } catch (RuntimeException error) {
                    store.preparationFailed(userId, creation, error.getMessage());
                    return;
                }
                var token = store.claimGeneration(userId, creation.queueId());
                if (token.isPresent()) runGeneration(userId, creation.queueId(), command.prefetchSize(), token.get(), null);
            });
        } catch (RejectedExecutionException error) {
            store.preparationFailed(userId, creation, "音频任务已满，请稍后使用新的请求重新提交");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "音频任务已满，本次未执行生成，请稍后重试");
        }
        return status(userId, creation.queueId());
    }

    /** Always reads MySQL first. Stale Redis data can never decide what gets executed. */
    public Map<String, Object> status(long userId, String queueId) {
        var durable = store.statusIfPresent(userId, queueId);
        if (durable.isPresent()) {
            cache(durable.get());
            return durable.get();
        }
        return legacySnapshot(userId, queueId);
    }

    /** 完整讲稿：把各段正文按顺序拼成一份，供查看与导出。 */
    public String script(long userId, String queueId) {
        return store.scriptForUser(userId, queueId);
    }

    /**
     * 换音色重念：讲稿与内容不变，只把已有片段用新音色重新合成。
     * 不调用模型，因此没有 token 成本；代价是同量级的语音合成时间。
     */
    public Map<String, Object> revoice(long userId, String queueId, String voice) {
        String requested = voice == null ? "" : voice.strip();
        if (requested.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "请选择要使用的音色");
        var command = store.commandForUser(userId, queueId);
        var snapshot = store.status(userId, queueId);
        // 每段内容的语言都必须被新音色支持，否则在开始合成前就拒绝，避免产生半成品。
        Set<String> languages = new LinkedHashSet<>();
        if (snapshot.get("lessons") instanceof List<?> lessons)
            for (Object item : lessons)
                if (item instanceof Map<?, ?> lesson && lesson.get("script") instanceof String text && !text.isBlank())
                    languages.add(SpeechService.detectLanguage(text));
        for (String language : languages) speech.selectVoice(requested, language);
        var updated = new QueueCommand(command.topic(), command.projectId(), command.courseMinutes(),
                command.segmentMinutes(), command.audience(), requested, command.prefetchSize(),
                command.requestId(), command.mode(), command.instruction(), command.sourceText(),
                command.sourceTitle(), command.context());
        var outcome = store.revoice(userId, queueId, requested, json.writeValueAsString(updated));
        // 旧音频先留着（默认 8 小时），到点由 AudioRetention 清理。
        if (retention != null && outcome.get("retired") instanceof List<?> retired)
            for (Object audioId : retired) retention.retire(String.valueOf(audioId));
        generate(userId, queueId, ((Number) outcome.get("segments")).intValue());
        return status(userId, queueId);
    }

    /**
     * 基于当前讲稿提出某段修改：可以给修改意见或手工文本，用户确认前不应用。
     */
    public Map<String, Object> reviseSegment(long userId, String queueId, int number, String instruction, String text) {
        return reviseSegment(userId, queueId, number, instruction, text, () -> { });
    }

    public Map<String, Object> reviseSegment(long userId, String queueId, int number, String instruction, String text,
            Runnable mutationGuard) {
        return reviseSegment(userId, queueId, number, instruction, text, null, mutationGuard);
    }

    public Map<String, Object> reviseSegment(long userId, String queueId, int number, String instruction, String text,
            String expectedScriptHash, Runnable mutationGuard) {
        var baseline = store.scriptRevision(userId, queueId, number);
        String current = baseline.script();
        if (expectedScriptHash != null && !expectedScriptHash.isBlank()
                && !com.bedouyu.audiorag.common.Hashing.sha256(current).equalsIgnoreCase(expectedScriptHash.strip()))
            throw new ApiException(HttpStatus.CONFLICT, "这段讲稿已在别处更新，请核对最新正文后再保存；你的输入仍保留");
        boolean manual = text != null && !text.isBlank();
        String edited;
        if (manual) {
            edited = text.strip();
        } else {
            String request = instruction == null ? "" : instruction.strip();
            if (request.length() < 2) throw new ApiException(HttpStatus.BAD_REQUEST, "请写下想在这段讲稿基础上怎么改");
            if (reviser == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "讲稿改写不可用，请改为手动编辑后保存");
            edited = reviser.revise(current, request).strip();
        }
        if (edited.length() < 10 || edited.length() > 20000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "改后的这段讲稿需为 10 到 20000 个字符");
        ScriptEditPolicy.validateLanguage(current, edited, instruction, manual);
        // 长度保护只针对模型改写：手工编辑是用户自己写的，长短由他决定。
        if (!manual && !ScriptEditPolicy.allowsLengthChange(current, instruction)) {
            double ratio = (double) edited.length() / Math.max(1, current.length());
            if (ratio < 0.5 || ratio > 2.0)
                throw new ApiException(HttpStatus.BAD_REQUEST, "改动幅度过大（" + current.length() + " 字 → "
                        + edited.length() + " 字），请缩小改动范围后重试");
        }
        mutationGuard.run();
        if (edited.equals(current)) return store.status(userId, queueId);
        return store.proposeScriptRevision(userId, queueId, "REVISE", number, List.of(edited),
                manual ? "手工编辑" : instruction.strip(), baseline, mutationGuard);
    }

    /**
     * 整篇重写：改格式或结构时用（例如“不要导读，只要英语-汉语-英语两遍-下一句”）。
     * 结果先成为待确认修改；确认后再写回并合成第一段。
     */
    public Map<String, Object> rewriteWhole(long userId, String queueId, String instruction) {
        return rewriteWhole(userId, queueId, instruction, () -> { });
    }

    public Map<String, Object> rewriteWhole(long userId, String queueId, String instruction, Runnable mutationGuard) {
        return rewriteWhole(userId, queueId, instruction, mutationGuard, null);
    }

    private Map<String, Object> rewriteWhole(long userId, String queueId, String instruction, Runnable mutationGuard,
            String requestKey) {
        String request = instruction == null ? "" : instruction.strip();
        if (request.length() < 2)
            throw new ApiException(HttpStatus.BAD_REQUEST, "请写下整篇讲稿想怎么改");
        if (reviser == null)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "讲稿改写不可用，请改为手动编辑后保存");
        var baseline = store.fullScriptRevision(userId, queueId);
        String full = baseline.script();
        if (full.isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "这条音频还没有讲稿，不能整篇重写");
        int parts = Math.max(1, baseline.segments().size());
        String rewritten = reviser.rewriteWhole(full, request).strip();
        if (rewritten.length() < 10 || rewritten.length() > 60_000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "重写后的讲稿需为 10 到 60000 个字符");
        ScriptEditPolicy.validateLanguage(full, rewritten, request, false);
        mutationGuard.run();
        return store.proposeScriptRevision(userId, queueId, "REWRITE", null,
                rewritten.equals(full) ? List.of(full) : splitInto(rewritten, parts), request, baseline, mutationGuard, requestKey);
    }

    /**
     * 追加：用户说“再生成一些”“多来几句”时，只在现有讲稿后面续写新内容，
     * 原有分段与音频保持不变，续写也须由用户确认后才应用。
     */
    public Map<String, Object> extendWhole(long userId, String queueId, String instruction) {
        return extendWhole(userId, queueId, instruction, () -> { });
    }

    public Map<String, Object> extendWhole(long userId, String queueId, String instruction, Runnable mutationGuard) {
        return extendWhole(userId, queueId, instruction, mutationGuard, null);
    }

    private Map<String, Object> extendWhole(long userId, String queueId, String instruction, Runnable mutationGuard,
            String requestKey) {
        String request = instruction == null ? "" : instruction.strip();
        if (request.length() < 2)
            throw new ApiException(HttpStatus.BAD_REQUEST, "请写下要续写什么、多长、什么难度");
        if (reviser == null)
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "讲稿续写不可用，请改为手动编辑后保存");
        var baseline = store.fullScriptRevision(userId, queueId);
        String full = baseline.script();
        if (full.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "这条音频还没有讲稿，不能续写");
        String tail = full.length() <= 1200 ? full : full.substring(full.length() - 1200);
        String addition = reviser.extend(tail, request).strip();
        if (addition.length() < 20 || addition.length() > 20_000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "续写内容需为 20 到 20000 个字符");
        ScriptEditPolicy.validateLanguage(tail, addition, request, false);
        int segments = Math.max(1, baseline.segments().size());
        int average = Math.max(200, full.length() / segments);
        List<String> parts = splitInto(addition, Math.max(1, (int) Math.ceil(addition.length() / (double) average)));
        mutationGuard.run();
        return store.proposeScriptRevision(userId, queueId, "EXTEND", null, parts, request, baseline, mutationGuard, requestKey);
    }

    /** Natural-language edits share the same persisted, explicit user review boundary as chat tools. */
    public Map<String, Object> proposeInstruction(long userId, String queueId, String instruction, String kind,
            String requestKey) {
        var replay = store.existingScriptRevision(userId, queueId, requestKey);
        if (replay.isPresent()) return replay.get();
        if ("REWRITE".equals(kind)) return rewriteWhole(userId, queueId, instruction, () -> { }, requestKey);
        if ("EXTEND".equals(kind)) return extendWhole(userId, queueId, instruction, () -> { }, requestKey);
        throw new ApiException(HttpStatus.BAD_REQUEST, "不支持的讲稿修改类型");
    }

    public Map<String, Object> scriptRevisions(long userId, String queueId) {
        return Map.of("revisions", store.scriptRevisions(userId, queueId));
    }

    public Map<String, Object> acceptScriptRevision(long userId, String queueId, String revisionId, boolean confirmed) {
        return acceptScriptRevision(userId, queueId, revisionId, confirmed, null);
    }

    public Map<String, Object> acceptScriptRevision(long userId, String queueId, String revisionId, boolean confirmed,
            String voiceOverride) {
        if (confirmed) {
            var proposal = store.scriptRevisionForReview(userId, queueId, revisionId);
            if ("PENDING".equals(proposal.get("status"))) {
                String language = SpeechService.detectLanguage(String.valueOf(proposal.get("after_text")));
                var command = store.commandForUser(userId, queueId);
                try {
                    speech.selectVoice(voiceOverride == null ? command.voice() : voiceOverride.strip(), language);
                } catch (ApiException unavailableVoice) {
                    throw new ApiException(unavailableVoice.status(), "修改尚未应用，待审稿已保留。请先选择支持新讲稿语言的音色后再确认："
                            + unavailableVoice.getMessage());
                }
            }
        }
        var decision = voiceOverride == null ? store.decideScriptRevision(userId, queueId, revisionId, confirmed, true)
                : store.decideScriptRevision(userId, queueId, revisionId, confirmed, true, voiceOverride.strip());
        if (decision.stale()) throw new ApiException(HttpStatus.CONFLICT,
                "原稿已经改变，这份修改已标记为过期；请查看最新讲稿后重新提出修改");
        if (!decision.applied()) return decision.snapshot();
        if (retention != null) for (String audioId : decision.retired()) retention.retire(audioId);
        // This runs only after the transactional store committed ACCEPTED and the new live text.
        // Replayed acceptance never starts synthesis again, even if dispatch previously failed.
        try {
            generate(userId, queueId, 1, decision.firstSegment());
        } catch (RuntimeException dispatchError) {
            var result = new LinkedHashMap<>(store.status(userId, queueId));
            result.put("script_revision", decision.snapshot().get("script_revision"));
            result.put("synthesis_notice", "修改已确认并保存，音频尚未开始生成；可稍后继续合成");
            return result;
        }
        var result = new LinkedHashMap<>(store.status(userId, queueId));
        result.put("script_revision", decision.snapshot().get("script_revision"));
        return result;
    }

    public Map<String, Object> rejectScriptRevision(long userId, String queueId, String revisionId, boolean confirmed) {
        return store.decideScriptRevision(userId, queueId, revisionId, confirmed, false).snapshot();
    }

    public Map<String, Object> revertScriptRevision(long userId, String queueId, String revisionId, boolean confirmed) {
        return store.revertScriptRevision(userId, queueId, revisionId, confirmed);
    }
    /** 把整篇文本按句子边界均匀装进 parts 段，尽量保持用户熟悉的页数。 */
    static List<String> splitInto(String script, int parts) {
        String text = script == null ? "" : script.strip();
        if (text.isEmpty()) return List.of();
        if (parts <= 1) return List.of(text);
        List<String> sentences = new ArrayList<>();
        // 仅在边界切开，不吃掉空行/空格；否则装回同一页时英文单词会粘连。
        for (String piece : text.split("(?<=[。！？!?；;])|(?<=\\n)(?=\\s*\\n)")) {
            if (!piece.isBlank()) sentences.add(piece);
        }
        if (sentences.size() <= 1) return List.of(text);
        int target = Math.max(1, (int) Math.ceil(text.length() / (double) parts));
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() > 0 && current.length() + sentence.length() > target
                    && result.size() < parts - 1) {
                result.add(current.toString().strip());
                current.setLength(0);
            }
            current.append(sentence);
        }
        if (current.length() > 0) result.add(current.toString().strip());
        return result.isEmpty() ? List.of(text) : result;
    }

    /** 撤回上一版讲稿：先生成差异提案，由用户确认后恢复。 */
    public Map<String, Object> undoRevision(long userId, String queueId, int number) {
        return undoRevision(userId, queueId, number, () -> { });
    }

    public Map<String, Object> undoRevision(long userId, String queueId, int number, Runnable mutationGuard) {
        var baseline = store.scriptRevision(userId, queueId, number);
        String previous = store.previousScript(userId, queueId, number)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "这一段还没有可撤回的改稿"));
        return store.proposeScriptRevision(userId, queueId, "UNDO", number, List.of(previous),
                "撤回上一版", baseline, mutationGuard);
    }

    public Map<String, Object> refill(long userId, String queueId) {
        return refill(userId, queueId, 0);
    }

    /**
     * 按需继续合成：{@code segments} 为本次最多合成几段（0 表示沿用队列的预取设置，默认 1）。
     * 讲稿在准备阶段就已全部生成，这里只决定合成多少段音频。
     */
    public Map<String, Object> refill(long userId, String queueId, int segments) {
        var current = store.statusIfPresent(userId, queueId);
        if (current.isEmpty()) {
            legacySnapshot(userId, queueId);
            throw new ApiException(HttpStatus.CONFLICT, "旧 Redis 队列仅支持查看，不自动迁移或重新生成；请建立新的课程队列");
        }
        int maximum = segments > 0 ? Math.min(50, segments)
                : ((Number) current.get().get("prefetch_size")).intValue();
        generate(userId, queueId, Math.max(1, maximum));
        return status(userId, queueId);
    }

    public Map<String, Object> recoverSegment(long userId, String queueId, int segmentNumber, boolean confirmed,
            String expectedHash, String expectedAudioId) {
        var candidate = store.recoveryCandidate(userId, queueId, segmentNumber, confirmed, expectedHash, expectedAudioId);
        if ("READY".equals(candidate.status())) return status(userId, queueId);
        try {
            WavVerifier.validate(speech.audioFile(expectedAudioId + ".wav"));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.CONFLICT, "预留音频不完整，不能恢复：" + e.getMessage());
        }
        var result = store.recovered(userId, queueId, segmentNumber, expectedHash, expectedAudioId);
        cache(result);
        return result;
    }

    public Map<String, Object> resetSegment(long userId, String queueId, int segmentNumber, boolean confirmed,
            String expectedHash, String expectedAudioId, String reason) {
        var result = store.reset(userId, queueId, segmentNumber, confirmed, expectedHash, expectedAudioId, reason);
        cache(result);
        return result;
    }

    private void learnSpeechRate(String script, String audioId) {
        try {
            calibration.learn(script, WavVerifier.durationSeconds(speech.audioFile(audioId + ".wav")));
        } catch (IOException | RuntimeException ignored) {
            // 校准是可选优化，读不到时长不能影响已经成功的合成结果。
        }
    }

    private void generate(long userId, String queueId, int maximum) {
        generate(userId, queueId, maximum, null);
    }

    private void generate(long userId, String queueId, int maximum, Integer firstSegment) {
        var token = store.claimGeneration(userId, queueId);
        if (token.isEmpty()) return;
        try {
            executor.submit(() -> runGeneration(userId, queueId, maximum, token.get(), firstSegment));
        } catch (RejectedExecutionException error) {
            store.releaseGeneration(userId, queueId, token.get());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "音频工作线程已满或已停止，队列已释放；请稍后手动继续，不会在后台排队");
        }
    }

    private void runGeneration(long userId, String queueId, int maximum, String token, Integer firstSegment) {
                try {
                    for (int offset = 0; offset < maximum && !Thread.currentThread().isInterrupted(); offset++) {
                        var next = firstSegment == null ? store.claimNext(userId, queueId, token)
                                : store.claimNext(userId, queueId, token, firstSegment);
                        if (next.isEmpty()) break;
                        var claim = next.get();
                        try {
                            String url = speech.synthesize(claim.audioId(), claim.draft().script(), claim.command().voice());
                            WavVerifier.validate(speech.audioFile(claim.audioId() + ".wav"));
                            learnSpeechRate(claim.draft().script(), claim.audioId());
                            store.complete(userId, claim, url);
                        } catch (Exception error) {
                            store.uncertain(userId, claim, error.getMessage());
                            break; // Provider may have succeeded: never automatically invoke TTS again.
                        }
                        cache(store.status(userId, queueId));
                    }
                } finally {
                    store.releaseGeneration(userId, queueId, token);
                    cache(store.status(userId, queueId));
                }
    }

    /** Redis failure does not roll back already committed database work. */
    private void cache(Map<String, Object> snapshot) {
        try {
            redis.execute(CACHE_SNAPSHOT, List.of("audiorag:continuous:v2:" + snapshot.get("queue_id")),
                    snapshot.get("version").toString(), json.writeValueAsString(snapshot));
        } catch (RuntimeException ignored) {
            // status() rebuilds from MySQL; no automatic generation follows a cache miss.
        }
    }

    private Map<String, Object> legacySnapshot(long userId, String queueId) {
        if (queueId == null || !queueId.matches("[0-9a-fA-F-]{36}")) throw missing();
        try {
            String value = redis.opsForValue().get("audiorag:continuous:" + queueId);
            if (value == null) throw missing();
            JsonNode root = json.readTree(value);
            if (root.path("user_id").asLong() != userId
                    || !store.ownsProject(userId, root.path("command").path("projectId").asLong())) throw missing();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("queue_id", queueId);
            result.put("lessons", json.convertValue(root.path("lessons"), new TypeReference<List<Map<String, Object>>>() {}));
            result.put("errors", List.of("旧队列仅支持查看，不会根据 Redis 快照自动回放模型或合成音频"));
            result.put("generating", 0); result.put("total_segments", root.path("scripts").size());
            result.put("status", "LEGACY_READ_ONLY"); result.put("storage", "legacy-redis-read-only");
            return result;
        } catch (ApiException error) { throw error; }
        catch (RuntimeException error) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "旧队列快照暂不可读取"); }
    }

    public boolean ready() {
        try (var connection = redis.getConnectionFactory().getConnection()) {
            return "PONG".equals(connection.ping());
        } catch (RuntimeException error) { return false; }
    }

    public boolean durableReady() {
        try { return store.ready(); }
        catch (RuntimeException error) { return false; }
    }

    public void clearProject(long userId, long projectId) {
        store.cancelProject(userId, projectId);
        // Do not delete legacy snapshots or audio files. Late callbacks cannot revive CANCELLED state.
    }

    private List<String> splitCourse(String script, int segmentMinutes) {
        if (script == null || script.isBlank()) throw new IllegalArgumentException("课程讲稿为空");
        int target = (int) Math.max(600, SpeechTiming.targetCharacters(script, segmentMinutes) * calibration.scale());
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : script.split("\\n\\s*\\n")) {
            String text = paragraph.trim();
            if (text.isBlank()) continue;
            if (current.length() > 0 && current.length() + text.length() > target) {
                parts.add(current.toString().trim()); current.setLength(0);
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(text);
        }
        if (current.length() > 0) parts.add(current.toString().trim());
        return parts.isEmpty() ? List.of(script) : parts;
    }

    /** Preserve every original character; generated script splitting may normalise paragraph boundaries. */
    private List<String> splitExact(String script, int minutes) {
        int target = (int) Math.max(260, SpeechTiming.targetCharacters(script, minutes) * calibration.scale());
        List<String> result = new ArrayList<>();
        for (int start = 0; start < script.length();) {
            int end = Math.min(script.length(), start + target);
            if (end < script.length() && Character.isHighSurrogate(script.charAt(end - 1))) end--;
            if (end < script.length()) {
                int paragraph = script.lastIndexOf('\n', end);
                if (paragraph > start + target / 2) end = paragraph + 1;
            }
            String piece = script.substring(start, end);
            if (piece.isBlank()) {
                if (!result.isEmpty()) result.set(result.size() - 1, result.get(result.size() - 1) + piece);
                else {
                    int firstText = end;
                    while (firstText < script.length() && Character.isWhitespace(script.charAt(firstText))) firstText++;
                    end = Math.min(script.length(), firstText + target);
                    if (end < script.length() && Character.isHighSurrogate(script.charAt(end - 1))) end--;
                    result.add(script.substring(start, end));
                }
            } else result.add(piece);
            start = end;
        }
        return result;
    }

    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "连续播放队列不存在"); }
    @PreDestroy void close() { executor.shutdownNow(); }

    public record QueueCommand(String topic, long projectId, int courseMinutes, int segmentMinutes, String audience,
            String voice, int prefetchSize, String requestId, String mode,
            String instruction, String sourceText, String sourceTitle, Map<String, Object> context) {
        public QueueCommand { if (mode == null) mode = "CONTINUOUS"; context = context == null ? Map.of() : Map.copyOf(context); }
        public QueueCommand(String topic, long projectId, int courseMinutes, int segmentMinutes, String audience,
                String voice, int prefetchSize, String requestId, String mode) {
            this(topic, projectId, courseMinutes, segmentMinutes, audience, voice, prefetchSize, requestId, mode, null, null, null, Map.of());
        }
        public QueueCommand(String topic, long projectId, int courseMinutes, int segmentMinutes, String audience,
                String voice, int prefetchSize) { this(topic, projectId, courseMinutes, segmentMinutes, audience, voice, prefetchSize, null, "CONTINUOUS"); }
        public QueueCommand(String topic, long projectId, int courseMinutes, int segmentMinutes, String audience,
                String voice, int prefetchSize, String requestId) { this(topic, projectId, courseMinutes, segmentMinutes, audience, voice, prefetchSize, requestId, "CONTINUOUS"); }
    }
}
