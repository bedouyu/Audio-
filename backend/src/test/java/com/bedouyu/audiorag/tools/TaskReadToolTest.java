package com.bedouyu.audiorag.tools;

import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.service.*;
import com.bedouyu.audiorag.tools.agent.TaskReadTool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class TaskReadToolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentRunStore runs = mock(AgentRunStore.class);
    private final AgentArtifactStore artifacts = mock(AgentArtifactStore.class);
    private final TaskReadTool tool = new TaskReadTool(runs, artifacts, json);
    private final AgentExecutionContext context = new AgentExecutionContext("current", "session", "supervisor", 1, 7,
            new AgentBudget(), new EvidenceLedger(), null, null, ignored -> {});
    @Test void taskFromAnotherSessionCannotBeUsedAsCurrentMemory() {
        when(runs.get(1, 7, "old")).thenReturn(Map.of("session_id", "other", "goal", "other goal"));
        assertThatThrownBy(() -> tool.execute(json.readTree("{\"run_id\":\"old\"}"), context)).hasMessageContaining("当前会话");
        verifyNoInteractions(artifacts);
    }
    @Test void failedTaskReturnsRealGoalAndStatusWithoutReplay() {
        when(runs.get(1, 7, "old")).thenReturn(Map.of("session_id", "session", "goal", "按照最初目标生成课程", "status", "FAILED", "summary", "模型中断"));
        var result = json.valueToTree(tool.execute(json.readTree("{\"run_id\":\"old\"}"), context));
        assertThat(result.path("task_status").asText()).isEqualTo("FAILED");
        assertThat(result.path("goal").asText()).isEqualTo("按照最初目标生成课程");
        verify(runs, never()).start(anyLong(), anyLong(), anyString(), anyString());
    }
    @Test void taskGoalReadBudgetsJsonEscapingAndHasMore() {
        when(runs.get(1, 7, "old")).thenReturn(Map.of("session_id", "session", "goal", String.valueOf((char) 0).repeat(3000),
                "status", "FAILED", "summary", "中断"));
        var result = json.valueToTree(tool.execute(json.readTree("{\"run_id\":\"old\",\"maximum_chars\":2000}"), context));
        assertThat(json.writeValueAsString(result).length()).isLessThanOrEqualTo(7000);
        assertThat(result.path("has_more").asBoolean()).isTrue();
        assertThat(result.path("end").asInt()).isPositive();
    }
}
