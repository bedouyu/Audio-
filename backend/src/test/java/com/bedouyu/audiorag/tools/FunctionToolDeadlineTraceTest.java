package com.bedouyu.audiorag.tools;

import com.bedouyu.audiorag.agent.AgentProfile;
import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.model.dto.*;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.tools.execution.ToolExecutionRuntime;
import com.bedouyu.audiorag.tools.agent.DeclareTaskSpecificationTool;
import com.bedouyu.audiorag.service.AgentRunStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FunctionToolDeadlineTraceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

    @Test void pairsToolEventsByInternalAndOriginalModelCallIds() {
        try (var runtime = new ToolExecutionRuntime(1, 1, 1000)) {
            var executor = executor(runtime, tool("read", (args, context) -> Map.of("status", "OK")));
            var context = context();
            String response = executor.execute(new FunctionCall("model-call-42", "read", "{}"), profile("read"), context);
            assertThat(json.readTree(response).path("status").asText()).isEqualTo("OK");
            assertThat(events).hasSize(2);
            assertThat(events.get(0).get("call_id")).isEqualTo(events.get(1).get("call_id"));
            assertThat(events.get(0).get("tool_call_id")).isEqualTo("model-call-42");
            assertThat(events.get(1).get("tool_call_id")).isEqualTo("model-call-42");
            assertThat(events.get(0).get("event_id")).isNotEqualTo(events.get(1).get("event_id"));
            assertThat(events.get(0).get("invocation_id")).isEqualTo(context.invocationId);
            assertThat(events.get(1).get("status")).isEqualTo("COMPLETED");
        }
    }

    @Test void repeatedAgentDelegationsHaveDistinctInvocationsAndExplicitParentToolScopes() {
        try (var runtime = new ToolExecutionRuntime(1, 1, 1000)) {
            var context = context();
            var executor = executor(runtime, tool("delegate_agent", (args, parent) -> {
                var child = parent.child("writer", Set.of());
                child.emit("model_start", "", "RUNNING", "决策", Map.of());
                return Map.of("status", "OK");
            }));
            executor.execute(new FunctionCall("delegate-1", "delegate_agent", "{}"), profile("delegate_agent"), context);
            executor.execute(new FunctionCall("delegate-2", "delegate_agent", "{}"), profile("delegate_agent"), context);
            var starts = events.stream().filter(event -> "tool_start".equals(event.get("kind"))).toList();
            var children = events.stream().filter(event -> "model_start".equals(event.get("kind"))).toList();
            assertThat(children).hasSize(2);
            assertThat(children.get(0).get("invocation_id")).isNotEqualTo(children.get(1).get("invocation_id"));
            for (int i = 0; i < 2; i++) {
                assertThat(children.get(i).get("parent_invocation_id")).isEqualTo(context.invocationId);
                assertThat(children.get(i).get("parent_call_id")).isEqualTo(starts.get(i).get("call_id"));
                assertThat(children.get(i).get("call_id")).isEqualTo("");
            }
            context.emit("model_start", "", "RUNNING", "父作用域应已恢复", Map.of());
            assertThat(events.get(events.size() - 1).get("call_id")).isEqualTo("");
        }
    }

    @Test void completedScopeCannotBeReusedByLateBackgroundChildren() {
        AtomicReference<AgentExecutionContext> child = new AtomicReference<>();
        try (var runtime = new ToolExecutionRuntime(1, 1, 1000)) {
            var executor = executor(runtime, tool("read", (args, parent) -> {
                child.set(parent.child("writer", Set.of())); return Map.of("status", "OK");
            }));
            executor.execute(new FunctionCall("call", "read", "{}"), profile("read"), context());
            assertThatThrownBy(child.get()::assertActive).hasMessageContaining("调用已结束");
            assertThatThrownBy(() -> child.get().emit("artifact", "", "COMPLETED", "晚到", Map.of()))
                    .isInstanceOf(AgentBudget.AgentStopped.class);
            assertThat(events).hasSize(2);
        }
    }

    @Test void inlineDelegationWithNestedWorkerToolDoesNotDeadlockASingleWorkerPool() {
        try (var runtime = new ToolExecutionRuntime(1, 1, 1000)) {
            AtomicReference<FunctionToolExecutor> reference = new AtomicReference<>();
            FunctionTool delegate = tool("delegate_agent", (args, parent) -> {
                var child = parent.child("writer", Set.of());
                return json.readTree(reference.get().execute(new FunctionCall("child-read", "read", "{}"), profile("read"), child));
            });
            reference.set(executor(runtime, delegate, tool("read", (args, context) -> Map.of("status", "OK"))));
            String result = reference.get().execute(new FunctionCall("parent-delegate", "delegate_agent", "{}"),
                    profile("delegate_agent"), context());
            assertThat(json.readTree(result).path("status").asText()).isEqualTo("OK");
            var starts = events.stream().filter(event -> "tool_start".equals(event.get("kind"))).toList();
            assertThat(starts).hasSize(2);
            assertThat(starts.get(1).get("parent_call_id")).isEqualTo(starts.get(0).get("call_id"));
        }
    }

    @Test void timeoutProducesExplicitFailureAndNeverAnEndSuccessOrAutomaticRetry() throws Exception {
        CountDownLatch release = new CountDownLatch(1), exited = new CountDownLatch(1);
        AtomicReference<Throwable> late = new AtomicReference<>();
        try (var runtime = new ToolExecutionRuntime(1, 1, 200)) {
            FunctionTool stubborn = tool("read", (args, context) -> {
                while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
                try { context.emit("artifact", "", "COMPLETED", "晚到", Map.of()); }
                catch (Throwable error) { late.set(error); }
                finally { exited.countDown(); }
                return Map.of("status", "OK");
            });
            var executor = executor(runtime, stubborn);
            var context = context();
            String response = executor.execute(new FunctionCall("call", "read", "{}"), profile("read"), context);
            assertThat(json.readTree(response).path("error_code").asText()).isEqualTo("TOOL_TIMEOUT");
            assertThat(json.readTree(response).path("retryable").asBoolean()).isFalse();
            release.countDown(); assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(late.get()).isInstanceOf(AgentBudget.AgentStopped.class);
            assertThat(events).hasSize(2);
            assertThat(events.get(1)).containsEntry("status", "FAILED").containsEntry("error_code", "TOOL_TIMEOUT");
            assertThat(context.budget.stats().get("tool_calls")).isEqualTo(1);
        } finally { release.countDown(); }
    }

    @Test void blockedProjectOwnershipCheckAlsoHasATimeout() {
        CountDownLatch release = new CountDownLatch(1);
        var repository = mock(AudioRagRepository.class);
        when(repository.ownsProject(1, 7)).thenAnswer(ignored -> {
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException interruption) { }
            return true;
        });
        var read = mock(FunctionTool.class);
        when(read.definition()).thenReturn(new FunctionDefinition("read", "读取", ToolSchemas.object(Map.of())));
        when(read.hasSideEffects()).thenReturn(true);
        try (var runtime = new ToolExecutionRuntime(1, 1, 200)) {
            var executor = new FunctionToolExecutor(new FunctionToolRegistry(List.of(), List.of(read)),
                    new ToolArgumentValidator(), repository, json, runtime);
            String response = executor.execute(new FunctionCall("call", "read", "{}"), profile("read"), context());
            assertThat(json.readTree(response).path("error_code").asText()).isEqualTo("TOOL_TIMEOUT");
            release.countDown(); verify(read, never()).execute(any(), any());
        } finally { release.countDown(); }
    }

    @Test void timedOutStatefulToolStopsTheWholeRunAndMarksItsOutcomeUncertain() throws Exception {
        CountDownLatch release = new CountDownLatch(1), exited = new CountDownLatch(1);
        var context = context();
        FunctionTool stateful = new FunctionTool() {
            public boolean hasSideEffects() { return true; }
            public FunctionDefinition definition() { return new FunctionDefinition("declare", "写入", ToolSchemas.object(Map.of())); }
            public Object execute(JsonNode args, AgentExecutionContext execution) {
                while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
                exited.countDown(); return Map.of("status", "OK");
            }
        };
        try (var runtime = new ToolExecutionRuntime(1, 1, 200)) {
            var executor = executor(runtime, stateful);
            assertThatThrownBy(() -> executor.execute(new FunctionCall("call", "declare", "{}"), profile("declare"), context))
                    .isInstanceOfSatisfying(AgentBudget.AgentStopped.class,
                            stop -> assertThat(stop.status()).isEqualTo("TOOL_TIMEOUT")).hasMessageContaining("待人工核查");
            assertThat(events).hasSize(2);
            assertThat(events.get(1)).containsEntry("outcome_uncertain", true).containsEntry("status", "TOOL_TIMEOUT");
            assertThatThrownBy(context::assertActive).isInstanceOf(AgentBudget.AgentStopped.class);
            assertThatThrownBy(() -> executor.execute(new FunctionCall("retry", "declare", "{}"), profile("declare"), context))
                    .isInstanceOf(AgentBudget.AgentStopped.class);
            assertThat(context.budget.stats().get("tool_calls")).isEqualTo(1);
            release.countDown(); assertThat(exited.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
    }

    @Test void realTaskDeclarationCannotFreezeContextAfterItsDatabaseCallTimesOut() throws Exception {
        CountDownLatch release = new CountDownLatch(1), saveReturned = new CountDownLatch(1);
        var runs = mock(AgentRunStore.class);
        doAnswer(ignored -> {
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException interrupted) { }
            saveReturned.countDown(); return null;
        }).when(runs).saveSpecification(anyString(), any());
        var context = context();
        try (var runtime = new ToolExecutionRuntime(1, 1, 200)) {
            var executor = executor(runtime, new DeclareTaskSpecificationTool(runs));
            assertThatThrownBy(() -> executor.execute(new FunctionCall("call", "declare_task_specification",
                    "{\"required_artifacts\":[\"SCRIPT_DRAFT\"]}"), profile("declare_task_specification"), context))
                    .isInstanceOfSatisfying(AgentBudget.AgentStopped.class,
                            stop -> assertThat(stop.status()).isEqualTo("TOOL_TIMEOUT"));
            release.countDown(); assertThat(saveReturned.await(2, TimeUnit.SECONDS)).isTrue();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (runtime.activeWorkers() != 0 && System.nanoTime() < until) Thread.sleep(5);
            assertThat(runtime.activeWorkers()).isZero();
            assertThat(context.taskSpecificationFrozen).isFalse();
            assertThatThrownBy(context::assertActive).isInstanceOf(AgentBudget.AgentStopped.class);
            assertThat(events).hasSize(2);
            verify(runs, times(1)).saveSpecification(anyString(), any());
        } finally { release.countDown(); }
    }

    @Test void executorUsesTheModelBackedToolsWaitAllowance() {
        FunctionTool edit = new FunctionTool() {
            public FunctionDefinition definition() { return new FunctionDefinition("edit", "编辑", ToolSchemas.object(Map.of())); }
            public boolean hasSideEffects() { return true; }
            public long minimumTimeoutMillis() { return 2000; }
            public Object execute(JsonNode args, AgentExecutionContext context) {
                try { Thread.sleep(100); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); }
                context.assertActive();
                return Map.of("status", "saved");
            }
        };
        try (var runtime = new ToolExecutionRuntime(1, 1, 20, 20)) {
            var result = executor(runtime, edit).execute(new FunctionCall("edit-call", "edit", "{}"), profile("edit"), context());
            assertThat(json.readTree(result).path("status").asText()).isEqualTo("saved");
            assertThat(events.stream().filter(event -> "tool_heartbeat".equals(event.get("kind"))).toList())
                    .isNotEmpty().allSatisfy(event -> {
                        assertThat(event.get("tool")).isEqualTo("edit");
                        assertThat(event.get("call_id")).isEqualTo(events.get(0).get("call_id"));
                    });
        }
    }

    private FunctionToolExecutor executor(ToolExecutionRuntime runtime, FunctionTool... tools) {
        var repository = mock(AudioRagRepository.class); when(repository.ownsProject(1, 7)).thenReturn(true);
        return new FunctionToolExecutor(new FunctionToolRegistry(List.of(), List.of(tools)),
                new ToolArgumentValidator(), repository, json, runtime);
    }
    private FunctionTool tool(String name, BiFunction<JsonNode, AgentExecutionContext, Object> body) {
        return new FunctionTool() {
            public FunctionDefinition definition() { return new FunctionDefinition(name, name, ToolSchemas.object(Map.of())); }
            public Object execute(JsonNode args, AgentExecutionContext context) { return body.apply(args, context); }
        };
    }
    private AgentProfile profile(String... tools) { return new AgentProfile("supervisor", "主控", "", Set.of(tools), "", 10); }
    private AgentExecutionContext context() {
        return new AgentExecutionContext("run", "session", "supervisor", 1, 7,
                new AgentBudget(24, 32, Duration.ofSeconds(5)), new EvidenceLedger(), null, null, events::add);
    }
}
