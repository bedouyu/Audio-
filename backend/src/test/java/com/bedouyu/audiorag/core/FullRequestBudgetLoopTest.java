package com.bedouyu.audiorag.core;
import com.bedouyu.audiorag.agent.AgentProfile;
import com.bedouyu.audiorag.ai.AgentModelGateway;
import com.bedouyu.audiorag.config.PromptTemplates;
import com.bedouyu.audiorag.core.guard.AgentOutputValidator;
import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.model.dto.*;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.tools.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FullRequestBudgetLoopTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void rejectedPreflightDoesNotSendOrConsumeModelCallBudget() {
        AtomicInteger calls = new AtomicInteger();
        AgentModelGateway model = new AgentModelGateway() {
            public boolean configured() { return true; }
            public Map<String,Object> budgetSnapshot(List<Map<String,Object>> m, List<FunctionDefinition> t) { return Map.of("within_budget", false); }
            public AgentModelReply decide(List<Map<String,Object>> m, List<FunctionDefinition> t, Consumer<String> sink) { calls.incrementAndGet(); throw new AssertionError("should not send"); }
        };
        var context = context(new AgentBudget());
        assertThatThrownBy(() -> engine(model).run(profile(), "目标", List.of(), context, ignored -> {}))
                .isInstanceOfSatisfying(AgentBudget.AgentStopped.class, e -> assertThat(e.status()).isEqualTo("CONTEXT_LIMIT"));
        assertThat(calls).hasValue(0); assertThat(context.budget.stats()).containsEntry("model_calls", 0);
    }
    @Test void fullBudgetCompactsOnlyOldObservationAndKeepsLatestProtocolWhole() {
        List<List<Map<String,Object>>> sent = new ArrayList<>();
        AgentModelGateway model = new AgentModelGateway() {
            public boolean configured() { return true; }
            public Map<String,Object> budgetSnapshot(List<Map<String,Object>> m, List<FunctionDefinition> t) {
                return Map.of("within_budget", json.writeValueAsString(m).length() < 6500);
            }
            public AgentModelReply decide(List<Map<String,Object>> m, List<FunctionDefinition> t, Consumer<String> sink) {
                sent.add(m);
                return sent.size() <= 2 ? new AgentModelReply("", "", List.of(new FunctionCall("call_"+sent.size(), "search", "{\"query\":\"q"+sent.size()+"\"}")), "tool_calls")
                        : new AgentModelReply("完成", "", List.of(), "stop");
            }
        };
        assertThat(engine(model).run(profile(), "目标", List.of(), context(new AgentBudget()), ignored -> {})).isEqualTo("完成");
        var tools = sent.get(2).stream().filter(m -> "tool".equals(m.get("role"))).toList();
        assertThat(json.readTree(String.valueOf(tools.get(0).get("content"))).path("compaction_status").asText()).isEqualTo("COMPACTED");
        assertThat(json.readTree(String.valueOf(tools.get(1).get("content"))).path("text").asText()).hasSize(3500);
        assertThat(tools).extracting(m -> m.get("tool_call_id")).containsExactly("call_1", "call_2");
    }
    @Test void providerTimeoutAtSharedDeadlineKeepsLimitTerminalInsteadOfGenericFailure() {
        AgentModelGateway model = new AgentModelGateway() {
            public boolean configured() { return true; }
            public AgentModelReply decide(List<Map<String,Object>> m, List<FunctionDefinition> t, Consumer<String> sink) {
                try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                throw new IllegalStateException("provider deadline");
            }
        };
        assertThatThrownBy(() -> engine(model).run(profile(), "目标", List.of(),
                context(new AgentBudget(10,10,Duration.ofMillis(100))), ignored -> {}))
                .isInstanceOfSatisfying(AgentBudget.AgentStopped.class, e -> assertThat(e.status()).isEqualTo("LIMIT_REACHED"));
    }
    private ReActLoopEngine engine(AgentModelGateway model) {
        FunctionTool tool = mock(FunctionTool.class);
        when(tool.definition()).thenReturn(new FunctionDefinition("search", "查询", ToolSchemas.object(Map.of("query",ToolSchemas.string(20)), "query")));
        when(tool.execute(any(),any())).thenReturn(Map.of("status","OK","text","x".repeat(3500)));
        var registry = new FunctionToolRegistry(List.of(),List.of(tool));
        var repository = mock(AudioRagRepository.class); when(repository.ownsProject(1,7)).thenReturn(true);
        var prompts=mock(PromptTemplates.class); when(prompts.read(anyString())).thenReturn("prompt");
        return new ReActLoopEngine(model,registry,new FunctionToolExecutor(registry,new ToolArgumentValidator(),repository,json),
                prompts,mock(AgentOutputValidator.class),json,"structured");
    }
    private AgentProfile profile() { return new AgentProfile("test","测试","prompt",Set.of("search"),"",4); }
    private AgentExecutionContext context(AgentBudget budget) { return new AgentExecutionContext("run","session","test",1,7,budget,new EvidenceLedger(),null,null,ignored -> {}); }
}
