package com.bedouyu.audiorag.core.loop;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import com.bedouyu.audiorag.core.guard.ArtifactVerificationLedger;
import com.bedouyu.audiorag.core.guard.TaskSpecification;

/** 子 Agent 只有显式传入的产物和任务，不自动继承父 Agent 的完整对话。 */
public class AgentExecutionContext {
    public final String runId, sessionId, agentId;
    public final long userId, projectId;
    public final AgentBudget budget;
    public final EvidenceLedger evidence;
    public final Set<String> readableArtifacts;
    public final Delegate delegate;
    /** 一次 Agent 调用的实例，而不是 Agent 类型；同一类型的多次委派具有不同编号。 */
    public final String invocationId, parentInvocationId, parentCallId;
    private final ToolScope inheritedScope;
    private final ThreadLocal<ToolScope> activeScope = new ThreadLocal<>();
    private final Consumer<Map<String, Object>> events;
    public int iteration;
    public Boolean localScriptCheckPassed;
    public final ArtifactVerificationLedger verification = new ArtifactVerificationLedger();
    public volatile TaskSpecification taskSpecification = new TaskSpecification(Set.of(), "UNSPECIFIED");
    public volatile boolean taskSpecificationDeclared, taskSpecificationFrozen;
    public List<Map<String, Object>> recentTaskRecords = List.of();
    /** 公开历史的可追溯片段，仅主控显式设置；child()不继承。 */
    public Map<String, Object> memoryData = Map.of();
    /** 服务端确定性解析出的请求约束（语言、时长、篇幅），仅主控设置；child()不继承。 */
    public volatile String intentHint = "";
    public AgentExecutionContext(String runId, String sessionId, String agentId, long userId, long projectId,
            AgentBudget budget, EvidenceLedger evidence, Set<String> readableArtifacts,
            Delegate delegate, Consumer<Map<String, Object>> events) {
        this(runId, sessionId, agentId, userId, projectId, budget, evidence, readableArtifacts,
                delegate, events, null, null);
    }
    private AgentExecutionContext(String runId, String sessionId, String agentId, long userId, long projectId,
            AgentBudget budget, EvidenceLedger evidence, Set<String> readableArtifacts,
            Delegate delegate, Consumer<Map<String, Object>> events, String parentInvocation, ToolScope inherited) {
        this.runId = runId; this.sessionId = sessionId; this.agentId = agentId;
        this.userId = userId; this.projectId = projectId; this.budget = budget; this.evidence = evidence;
        this.readableArtifacts = readableArtifacts == null ? null
                : Collections.unmodifiableSet(new LinkedHashSet<>(readableArtifacts));
        this.delegate = delegate; this.events = events;
        this.invocationId = UUID.randomUUID().toString(); this.parentInvocationId = parentInvocation;
        this.inheritedScope = inherited; this.parentCallId = inherited == null ? null : inherited.callId;
    }
    public AgentExecutionContext child(String agent, Set<String> artifacts) {
        assertActive();
        return new AgentExecutionContext(runId, sessionId, agent, userId, projectId,
                budget, evidence, artifacts, null, events, invocationId, scope());
    }
    public void assertActive() {
        ToolScope scope = scope();
        if (scope != null) scope.assertActive();
        budget.check();
    }
    public ToolScope openToolScope(String modelToolCallId) {
        assertActive();
        return new ToolScope(modelToolCallId, scope());
    }
    /** 作用域仅在执行线程内生效，不将 parent.currentCall 写到共享上下文。 */
    public <T> T withToolScope(ToolScope scope, Supplier<T> work) {
        ToolScope previous = activeScope.get(); activeScope.set(scope);
        try { assertActive(); return work.get(); }
        finally { if (previous == null) activeScope.remove(); else activeScope.set(previous); }
    }
    private ToolScope scope() {
        ToolScope own = activeScope.get(); return own == null ? inheritedScope : own;
    }
    public void emit(String kind, String tool, String status, String detail, Map<String, Object> extra) {
        assertActive();
        emitPayload(activeScope.get(), kind, tool, status, detail, extra);
    }
    /** 只有等待工具的调用方记录终态；过期 worker 只能失败，不能再发送成功事件。 */
    public void emitToolOutcome(ToolScope scope, String tool, String status, String detail, Map<String, Object> extra) {
        if (Thread.currentThread() != scope.ownerThread)
            throw new IllegalStateException("只有等待工具的调用方可记录工具终态");
        if ("COMPLETED".equals(status)) { scope.assertActive(); budget.check(); }
        emitPayload(scope, "tool_end", tool, status, detail, extra);
    }
    private void emitPayload(ToolScope scope, String kind, String tool, String status, String detail, Map<String, Object> extra) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("run_id", runId); payload.put("agent_id", agentId);
        payload.put("iteration", iteration); payload.put("sequence", budget.nextSequence());
        payload.put("kind", kind); payload.put("tool", tool == null ? "" : tool);
        payload.put("status", status); payload.put("detail", detail);
        payload.put("event_id", UUID.randomUUID().toString());
        payload.put("occurred_at", java.time.Instant.now().toString());
        payload.put("invocation_id", invocationId);
        payload.put("parent_invocation_id", parentInvocationId == null ? "" : parentInvocationId);
        payload.put("call_id", scope == null ? "" : scope.callId);
        payload.put("tool_call_id", scope == null ? "" : scope.modelToolCallId);
        payload.put("parent_call_id", scope != null && scope.parent != null ? scope.parent.callId
                : parentCallId == null ? "" : parentCallId);
        if (scope != null) payload.put("elapsed_ms", scope.elapsedMillis());
        events.accept(payload);
    }
    public void requireArtifact(String id) {
        assertActive();
        if (readableArtifacts != null && !readableArtifacts.contains(id))
            throw new IllegalArgumentException("该产物没有传入当前子 Agent");
    }
    public static final class ToolScope {
        private final String callId = UUID.randomUUID().toString();
        private final String modelToolCallId;
        private final ToolScope parent;
        private final Thread ownerThread = Thread.currentThread();
        private final long startedNanos = System.nanoTime();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private volatile String stoppedStatus = "CANCELLED";
        private volatile String stoppedReason = "工具调用作用域已失效";
        private ToolScope(String modelToolCallId, ToolScope parent) {
            this.modelToolCallId = Objects.requireNonNull(modelToolCallId); this.parent = parent;
        }
        public String callId() { return callId; }
        public long elapsedMillis() { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos); }
        public void invalidate(String status, String reason) {
            stoppedStatus = status; stoppedReason = reason; active.set(false);
        }
        private void assertActive() {
            if (parent != null) parent.assertActive();
            if (!active.get()) throw new AgentBudget.AgentStopped(stoppedStatus, stoppedReason);
        }
    }
    @FunctionalInterface public interface Delegate {
        Map<String, Object> run(AgentExecutionContext parent, String agent, String task, List<String> artifacts);
    }
}
