package com.bedouyu.audiorag.tools;

import com.bedouyu.audiorag.agent.AgentProfile;
import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.model.dto.FunctionCall;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import com.bedouyu.audiorag.tools.execution.ToolExecutionRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class FunctionToolExecutor {
    private final FunctionToolRegistry registry;
    private final ToolArgumentValidator validator;
    private final AudioRagRepository repository;
    private final ObjectMapper json;
    private final ToolExecutionRuntime runtime;
    private static final ToolExecutionRuntime DEFAULT_RUNTIME = new ToolExecutionRuntime(4, 16, 30000);
    public FunctionToolExecutor(FunctionToolRegistry registry, ToolArgumentValidator validator,
                                AudioRagRepository repository, ObjectMapper json) {
        this(registry, validator, repository, json, DEFAULT_RUNTIME);
    }
    @Autowired
    public FunctionToolExecutor(FunctionToolRegistry registry, ToolArgumentValidator validator,
                                AudioRagRepository repository, ObjectMapper json, ToolExecutionRuntime runtime) {
        this.registry = registry; this.validator = validator; this.repository = repository; this.json = json;
        this.runtime = runtime;
    }
    public String execute(FunctionCall call, AgentProfile profile, AgentExecutionContext context) {
        context.assertActive();
        String canonical = call.arguments();
        try {
            var parsed = json.readTree(call.arguments());
            if (parsed.isObject()) {
                Map<String, Object> sorted = new TreeMap<>();
                parsed.properties().forEach(field -> sorted.put(field.getKey(), json.convertValue(field.getValue(), Object.class)));
                canonical = json.writeValueAsString(sorted);
            }
        } catch (RuntimeException ignored) { }
        context.budget.tool(profile.id(), call.name(), canonical);
        var scope = context.openToolScope(call.id());
        context.withToolScope(scope, () -> {
            context.emit("tool_start", call.name(), "RUNNING", "执行工具", Map.of("arguments", call.arguments()));
            return null;
        });
        AtomicBoolean sideEffectStarted = new AtomicBoolean();
        try {
            if (!profile.tools().contains(call.name())) throw new IllegalArgumentException("当前 Agent 无此工具权限");
            FunctionTool tool = registry.require(call.name());
            var args = json.readTree(call.arguments());
            validator.validate(args, tool.definition().parameters());
            // 委派在调用方继续运行，子工具再进入执行池；避免所有 worker 等待嵌套 worker 的死锁。
            Object value = "delegate_agent".equals(call.name())
                    ? delegate(tool, args, context, scope, sideEffectStarted)
                    : runtime.await(context, scope, () -> {
                        requireProject(context);
                        if (tool.hasSideEffects()) sideEffectStarted.set(true);
                        return tool.execute(args, context);
                    });
            context.withToolScope(scope, () -> { context.assertActive(); return null; });
            String observation = json.writeValueAsString(value);
            if (observation.length() > 8000)
                observation = json.writeValueAsString(Map.of("status", "TRUNCATED",
                        "preview", observation.substring(0, 7500), "message", "输出超出观察预算，请缩小检索或分段读取"));
            context.emitToolOutcome(scope, call.name(), "COMPLETED", "工具完成", Map.of("observation", observation));
            scope.invalidate("CALL_CLOSED", "工具调用已结束，禁止晚到写入或成功事件");
            return observation;
        } catch (AgentBudget.AgentStopped stop) {
            scope.invalidate(stop.status(), stop.getMessage());
            context.emitToolOutcome(scope, call.name(), stop.status(), stop.getMessage(), Map.of("error_code", stop.status()));
            throw stop;
        }
        catch (RuntimeException exception) {
            String detail = EvidenceLedger.clip(exception.getMessage() == null ? "工具执行失败" : exception.getMessage(), 500);
            String code = exception instanceof ToolExecutionRuntime.ToolExecutionFailure failure ? failure.code() : "TOOL_FAILED";
            if (Set.of("TOOL_TIMEOUT", "TOOL_RUNTIME_STOPPED").contains(code) && sideEffectStarted.get()) {
                String uncertain = "有状态工具超时或执行器关闭，可能已发生写入；任务已停止，结果待人工核查，不会自动重试";
                scope.invalidate(code, uncertain); context.budget.cancel(uncertain);
                context.emitToolOutcome(scope, call.name(), code, uncertain,
                        Map.of("error_code", code, "outcome_uncertain", true));
                throw new AgentBudget.AgentStopped(code, uncertain);
            }
            scope.invalidate(code, detail);
            context.emitToolOutcome(scope, call.name(), "FAILED", detail, Map.of("error_code", code));
            return json.writeValueAsString(Map.of("status", "ERROR", "error_code", code,
                    "message", detail, "retryable", false));
        }
    }
    private Object delegate(FunctionTool tool, tools.jackson.databind.JsonNode args,
                            AgentExecutionContext context, AgentExecutionContext.ToolScope scope, AtomicBoolean sideEffectStarted) {
        // 项目校验可能涉及数据库，同样要有界；真正的委派不占用工具 worker。
        runtime.await(context, scope, () -> { requireProject(context); return null; });
        return context.withToolScope(scope, () -> {
            if (tool.hasSideEffects()) sideEffectStarted.set(true);
            return tool.execute(args, context);
        });
    }
    private void requireProject(AgentExecutionContext context) {
        context.assertActive();
        if (!repository.ownsProject(context.userId, context.projectId)) throw new IllegalArgumentException("项目归属已变化");
        context.assertActive();
    }
}
