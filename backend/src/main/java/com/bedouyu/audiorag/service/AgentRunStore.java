package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.model.entity.AgentRunRecord;
import com.bedouyu.audiorag.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import com.bedouyu.audiorag.service.runtime.AgentRuntimeLeaseService;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.*;

@Service
public class AgentRunStore {
    private final AgentRunRecordRepository runs;
    private final AudioRagRepository repository;
    private final ObjectMapper json;
    private final AgentArtifactStore artifacts;
    private final AgentRuntimeLeaseService nodes;
    @PersistenceContext private EntityManager entityManager;
    /** Compatibility constructor for isolated Mockito tests, not a multi-instance runtime. */
    public AgentRunStore(AgentRunRecordRepository runs, AudioRagRepository repository, ObjectMapper json, AgentArtifactStore artifacts) {
        this(runs, repository, json, artifacts, null);
    }
    @Autowired public AgentRunStore(AgentRunRecordRepository runs, AudioRagRepository repository, ObjectMapper json,
            AgentArtifactStore artifacts, AgentRuntimeLeaseService nodes) {
        this.runs = runs; this.repository = repository; this.json = json;
        this.artifacts = artifacts; this.nodes = nodes;
    }
    @Transactional
    public String start(long user, long project, String session, String goal) {
        return start(user, project, session, goal, null, null);
    }
    @Transactional
    public String start(long user, long project, String session, String goal, String requestId, String requestHash) {
        requireProject(user, project);
        validateRequest(requestId, requestHash);
        if (requestId != null && runs.findByUserIdAndProjectIdAndRequestId(user, project, requestId).isPresent())
            throw conflict("此请求编号已使用，请读取原任务或使用新的请求编号");
        String id = UUID.randomUUID().toString();
        try {
            runs.saveAndFlush(new AgentRunRecord(id, user, project, session, goal, requestId, requestHash,
                    nodes == null ? null : nodes.ownerId()));
        } catch (DataIntegrityViolationException duplicate) {
            if (requestId == null) throw duplicate;
            throw conflict("此请求编号已使用，请读取原任务或使用新的请求编号");
        }
        return id;
    }
    @Transactional
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> replay(long user, long project, String requestId, String requestHash) {
        requireProject(user, project);
        validateRequest(requestId, requestHash);
        if (requestId == null) return Optional.empty();
        var existing = runs.lockedByRequest(user, project, requestId);
        if (existing.isEmpty()) return Optional.empty();
        var run = existing.get();
        reconcile(run);
        if (!Objects.equals(requestHash, run.getRequestHash()))
            throw conflict("同一请求编号不能用于不同的请求内容");
        if (!Set.of("SUCCEEDED", "AWAITING_CONFIRMATION").contains(run.getStatus()))
            throw conflict("原任务状态为 " + run.getStatus() + "；不会自动重试，请查看任务并使用新的请求编号");
        Map<String, Object> result = run.getResult() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(json.readValue(run.getResult(), Map.class));
        result.put("status", run.getStatus()); result.put("run_id", run.getId());
        result.put("session_id", run.getSessionId());
        result.put("artifacts", artifacts.forRun(user, project, run.getId()));
        result.put("trace", trace(run.getSessionId(), run.getId()));
        result.putIfAbsent("answer", ""); result.putIfAbsent("citations", List.of());
        result.putIfAbsent("stats", Map.of());
        return Optional.of(result);
    }
    @Transactional
    public void saveSpecification(String runId, Object specification) {
        var run = runs.locked(runId).orElseThrow(this::missing);
        String value = json.writeValueAsString(Objects.requireNonNull(specification, "specification"));
        if (run.getTaskSpecification() != null && !run.getTaskSpecification().equals(value))
            throw conflict("任务规格保存后不可修改");
        run.specification(value);
    }
    @Transactional
    public void event(String session, Map<String, Object> event) {
        // 轨迹只含动作、观察与状态；不包含 reasoning_content 和模型密钥。
        repository.appendEvent(session, ((Number) event.get("sequence")).intValue(),
                String.valueOf(event.get("kind")), String.valueOf(event.getOrDefault("tool", "")),
                event);
    }
    @Transactional
    @SuppressWarnings("unchecked")
    public void finish(String id, String status, String summary, Object result) {
        var run = runs.locked(id).orElseThrow(this::missing);
        reconcile(run);
        Set<String> completed = Set.of("SUCCEEDED", "AWAITING_CONFIRMATION");
        // 取消/失败是终态；迟到回调既不能复活任务，也不能把已完成任务改成失败。
        if (!"RUNNING".equals(run.getStatus()) && !completed.contains(run.getStatus())) return;
        if (completed.contains(run.getStatus()) && !completed.contains(status)) return;
        // 所有产物写入也先锁 run；终态由锁内的现有产物决定，不能使用调用方的旧 DRAFT 快照。
        var outputs = artifacts.forRun(run.getUserId(), run.getProjectId(), id);
        String next = status;
        if (completed.contains(status)) {
            boolean pending = outputs.stream().anyMatch(a -> Set.of("SCRIPT_DRAFT", "LEARNING_PLAN").contains(a.get("kind"))
                    && !Set.of("READY", "APPLIED", "CONSUMED", "SUPERSEDED").contains(a.get("status")));
            next = pending ? "AWAITING_CONFIRMATION" : "SUCCEEDED";
        }
        Map<String, Object> value = result == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(json.convertValue(result, Map.class));
        value.put("status", next); value.put("artifacts", outputs);
        value.put("run_id", id); value.put("session_id", run.getSessionId());
        run.finish(next, summary == null ? "" : summary, json.writeValueAsString(value));
    }
    @Transactional
    public List<Map<String, Object>> list(long user, long project) {
        requireProject(user, project);
        return runs.findTop30ByUserIdAndProjectIdOrderByCreatedAtDesc(user, project).stream()
                .map(run -> view(reconcileLocked(run), false)).toList();
    }
    @Transactional
    public Map<String, Object> get(long user, long project, String id) {
        requireProject(user, project);
        AgentRunRecord run = runs.locked(id).orElseThrow(() -> missing());
        if (run.getUserId() != user || run.getProjectId() != project) throw missing();
        reconcile(run);
        return view(run, true);
    }
    @Transactional
    public List<Map<String, Object>> recentContext(long user, long project, String session, String excluded) {
        requireProject(user, project); repository.requireSession(user, project, session);
        var candidates = runs.findTop20ByUserIdAndProjectIdAndSessionIdAndIdNotOrderByCreatedAtDesc(user, project, session, excluded);
        candidates = candidates.stream().map(this::reconcileLocked).toList();
        // 重复的“继续”不应把原始目标挤掉；这是有界状态恢复，不假装扫描了所有历史任务。
        var original = candidates.stream().filter(run -> !run.getGoal().strip()
                .matches("(?i)^(继续|继续执行|继续生成|下一步|接着做|continue)[。!！\\s]*$")).limit(3).toList();
        if (original.isEmpty()) original = candidates.stream().limit(3).toList();
        return original.stream().map(run -> Map.<String, Object>of("id", run.getId(), "status", run.getStatus(),
                        "goal", com.bedouyu.audiorag.core.loop.EvidenceLedger.clip(run.getGoal(), 1000),
                        "goal_truncated", run.getGoal().length() > 1000,
                        "summary", com.bedouyu.audiorag.core.loop.EvidenceLedger.clip(run.getSummary(), 500),
                        "artifacts", artifacts.forRun(user, project, run.getId()).stream().limit(6)
                            .map(a -> Map.of("id", a.get("id"), "kind", a.get("kind"), "status", a.get("status"))).toList(),
                        "run_scan_limit", 20)).toList();
    }
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> trace(String session, String runId) {
        return repository.events(session).stream().filter(event -> {
            var payload = (Map<String, Object>) event.get("payload");
            return runId.equals(payload.get("run_id"));
        }).toList();
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> view(AgentRunRecord run, boolean details) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", run.getId()); value.put("status", run.getStatus());
        value.put("goal", run.getGoal()); value.put("summary", run.getSummary());
        value.put("created_at", run.getCreatedAt()); value.put("session_id", run.getSessionId());
        value.put("request_id", run.getRequestId()); value.put("version", run.getVersion());
        value.put("owner_id", run.getOwnerId());
        value.put("task_specification", run.getTaskSpecification() == null ? null
                : json.readValue(run.getTaskSpecification(), Object.class));
        if (details) {
            if (run.getResult() == null) value.put("result", null);
            else {
                Map<String, Object> result = new LinkedHashMap<>(json.readValue(run.getResult(), Map.class));
                result.put("status", run.getStatus());
                result.put("artifacts", artifacts.forRun(run.getUserId(), run.getProjectId(), run.getId()));
                value.put("result", result);
            }
            value.put("trace", trace(run.getSessionId(), run.getId()));
        }
        return value;
    }
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void markInterrupted() {
        for (var candidate : runs.findByStatus("RUNNING")) reconcileLocked(candidate);
    }
    private AgentRunRecord reconcileLocked(AgentRunRecord candidate) {
        if (nodes == null || !"RUNNING".equals(candidate.getStatus()) || !nodes.abandoned(candidate.getOwnerId())) return candidate;
        var run = runs.locked(candidate.getId()).orElseThrow(this::missing);
        // The list query may already have loaded a stale managed entity before another writer finished.
        if (entityManager != null) entityManager.refresh(run, LockModeType.PESSIMISTIC_WRITE);
        reconcile(run); return run;
    }
    private void reconcile(AgentRunRecord run) {
        if (nodes != null && "RUNNING".equals(run.getStatus()) && nodes.abandoned(run.getOwnerId()))
            run.finish("INTERRUPTED", "执行节点租约缺失或过期；产物保留，工具未自动重放", null);
    }
    public void assertCurrentInstanceLease() { if (nodes != null) nodes.assertActive(); }
    @Transactional public void deleteProject(long id) {
        runs.findByProjectIdOrderByIdAsc(id).forEach(run -> runs.locked(run.getId()));
        runs.deleteByProjectId(id);
    }
    private void validateRequest(String requestId, String requestHash) {
        if (requestId == null && requestHash == null) return;
        try {
            if (requestId == null || !UUID.fromString(requestId).toString().equals(requestId))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "request_id 必须是规范 UUID");
        }
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "请求内容哈希无效");
    }
    private ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT, message); }
    private void requireProject(long user, long project) {
        if (!repository.ownsProject(user, project)) throw missing();
    }
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "Agent 任务不存在或不属于当前项目"); }
}
