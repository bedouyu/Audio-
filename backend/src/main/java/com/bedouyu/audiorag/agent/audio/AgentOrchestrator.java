package com.bedouyu.audiorag.agent.audio;

import com.bedouyu.audiorag.agent.AgentRegistry;
import com.bedouyu.audiorag.ai.AgentModelGateway;
import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.model.dto.ModelMessage;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.*;
import com.bedouyu.audiorag.memory.MemoryContextService;
import com.bedouyu.audiorag.core.guard.TaskSpecificationResolver;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;
import java.util.function.Consumer;

/** Supervisor 通过原生 delegate_agent 工具选择子 Agent；子 Agent 有独立循环和上下文。 */
@Service
public class AgentOrchestrator {
    private final ReActLoopEngine loop;
    private final AgentRegistry agents;
    private final AgentModelGateway model;
    private final AgentRunStore runs;
    private final AgentArtifactStore artifacts;
    private final AudioRagRepository repository;
    private final MemoryContextService memory;
    private final boolean enabled;
    private final int modelLimit;
    private final int toolLimit;
    private final AgentRequestCoordinator requests;
    private final TaskSpecificationResolver specifications;
    public AgentOrchestrator(ReActLoopEngine loop, AgentRegistry agents, AgentModelGateway model,
            AgentRunStore runs, AgentArtifactStore artifacts, AudioRagRepository repository,
            MemoryContextService memory, AgentRequestCoordinator requests, TaskSpecificationResolver specifications,
            @Value("${audiorag.agent.enabled:true}") boolean enabled,
            @Value("${audiorag.agent.model-limit:40}") int modelLimit,
            @Value("${audiorag.agent.tool-limit:48}") int toolLimit) {
        this.modelLimit = modelLimit; this.toolLimit = toolLimit;
        this.loop = loop; this.agents = agents; this.model = model; this.runs = runs;
        this.artifacts = artifacts; this.repository = repository; this.memory = memory; this.enabled = enabled;
        this.requests = requests; this.specifications = specifications;
    }
    public boolean available() { return enabled && model.configured(); }
    public Map<String, Object> chat(long user, AudioAgent.ChatCommand command, Consumer<String> token,
                                   Consumer<Map<String, Object>> sink) {
        return chat(user, command, token, sink, ignored -> { });
    }
    /** 先公布本次 run 的身份，调用失败后也能精确找回同一任务的已保存产物。 */
    public Map<String, Object> chat(long user, AudioAgent.ChatCommand command, Consumer<String> token,
                                   Consumer<Map<String, Object>> sink, Consumer<String> runStarted) {
        try (var reservation = requests.begin(user, command)) {
        if (reservation.replay() != null) {
            runStarted.accept(String.valueOf(reservation.replay().get("run_id")));
            return reservation.replay();
        }
        String session = reservation.sessionId();
        String run = reservation.runId();
        runStarted.accept(run);
        AgentBudget budget = new AgentBudget(modelLimit, toolLimit, java.time.Duration.ofMinutes(10));
        EvidenceLedger evidence = new EvidenceLedger();
        Consumer<Map<String, Object>> events = event -> {
            runs.assertCurrentInstanceLease();
            runs.event(session, event); sink.accept(event);
        };
        var context = new AgentExecutionContext(run, session, "supervisor", user, command.projectId(),
                budget, evidence, null, this::delegate, events);
        context.audioQueueId = command.audioQueueId();
        context.audioRequest = command.audioRequest();
        try {
            context.taskSpecification = specifications.resolve(command.message(), command.requiredArtifacts());
            context.taskSpecificationDeclared = !context.taskSpecification.requiredArtifacts().isEmpty()
                    || command.requiredArtifacts() != null;
            context.emit("task_specification", "", "RUNNING", "独立最低完成规格已建立",
                    Map.of("task_specification", context.taskSpecification.data()));
            context.recentTaskRecords = runs.recentContext(user, command.projectId(), session, run);
            try {
                // 服务端已经把语言/时长/日期解析成确定值，直接告诉主控，省掉一轮模型自行猜测。
                context.intentHint = com.bedouyu.audiorag.service.content.AudioIntent
                        .resolve(command.message(), 5).agentHint();
            } catch (RuntimeException ignored) {
                // 解析失败（例如超出 1—60 分钟）时退回由模型自行理解，不阻断任务。
            }
            var prepared = memory.prepare(user, command.projectId(), session, command.message(), command.memoryStrategy());
            context.memoryData = prepared.modelData();
            context.emit("memory_prepare", "", "COMPLETED", "会话上下文已按策略组装", Map.of("memory", context.memoryData));
            String answer = loop.run(agents.require("supervisor"), command.message(), prepared.pack().messages(), context,
                    token == null ? ignored -> { } : token);
            runs.saveSpecification(run, context.taskSpecification.data());
            var outputs = artifacts.forRun(user, command.projectId(), run);
            // 音频请求的正文随后由队列消费并合成，不属于“等你确认”的聊天产物。
            boolean awaiting = !command.audioRequest() && outputs.stream()
                    .anyMatch(a -> Set.of("SCRIPT_DRAFT", "LEARNING_PLAN").contains(a.get("kind"))
                            && "DRAFT".equals(a.get("status")));
            if (awaiting) answer = "本轮有待你确认的音频产物，请在草稿页审核后执行。\n\n" + answer;
            context.assertActive();
            runs.assertCurrentInstanceLease();
            if (!command.audioRequest()) {
                repository.appendTurn(session, command.message(), answer);
                repository.addProjectRecord(command.projectId(), "chat", "多 Agent 任务", answer,
                        Map.of("session_id", session, "run_id", run, "citations", evidence.citations().size()));
            }
            String status = awaiting ? "AWAITING_CONFIRMATION" : "SUCCEEDED";
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("session_id", session); result.put("run_id", run); result.put("answer", answer);
            result.put("citations", evidence.citations()); result.put("artifacts", outputs);
            result.put("status", status); result.put("stats", budget.stats());
            result.put("memory_strategy", prepared.pack().strategy());
            runs.finish(run, status, "Agent 返回结果；" + outputs.size() + " 个产物", result);
            @SuppressWarnings("unchecked")
            Map<String, Object> committed = new LinkedHashMap<>((Map<String, Object>) runs.get(user, command.projectId(), run).get("result"));
            committed.put("trace", runs.trace(session, run));
            return committed;
        } catch (RuntimeException exception) {
            try { runs.saveSpecification(run, context.taskSpecification.data()); }
            catch (RuntimeException bookkeeping) { exception.addSuppressed(bookkeeping); }
            String status = exception instanceof AgentBudget.AgentStopped stop ? stop.status()
                    : Thread.currentThread().isInterrupted() ? "CANCELLED" : "FAILED";
            runs.finish(run, status, EvidenceLedger.clip(exception.getMessage() == null ? "任务失败" : exception.getMessage(), 900),
                    Map.of("stats", budget.stats(), "artifacts", artifacts.forRun(user, command.projectId(), run)));
            throw exception;
        }
        }
    }
    private Map<String, Object> delegate(AgentExecutionContext parent, String id, String task, List<String> inputs) {
        if ("supervisor".equals(id)) throw new IllegalArgumentException("不能递归委派主控");
        inputs.forEach(artifact -> artifacts.get(parent.userId, parent.projectId, artifact));
        var profile = agents.require(id);
        var child = parent.child(id, new LinkedHashSet<>(inputs));
        // 请求的语言与时长是整条链路的约束，不依赖主控在 task 里转述一次。
        child.intentHint = parent.intentHint;
        child.emit("delegate_start", "delegate_agent", "RUNNING", "进入" + profile.title(), Map.of("input_artifact_ids", inputs));
        String text = loop.run(profile, task, List.<ModelMessage>of(), child, ignored -> { });
        child.assertActive();
        parent.assertActive();
        runs.assertCurrentInstanceLease();
        Map<String, Object> artifact = artifacts.save(parent.runId, parent.userId, parent.projectId,
                id, profile.artifactKind(), text, inputs, parent.evidence.citations());
        child.emit("artifact", "", "COMPLETED", profile.title() + "产物已保存",
                Map.of("artifact_id", artifact.get("id"), "artifact_kind", profile.artifactKind()));
        return Map.of("status", "OK", "agent_id", id, "artifact_id", artifact.get("id"),
                "artifact_kind", profile.artifactKind(), "summary", EvidenceLedger.clip(text, 1800));
    }
}
