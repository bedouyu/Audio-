package com.bedouyu.audiorag.core;

import com.bedouyu.audiorag.agent.AgentRegistry;
import com.bedouyu.audiorag.core.guard.*;
import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.service.AgentRunStore;
import com.bedouyu.audiorag.tools.agent.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskSpecificationTest {
    private final TaskSpecificationResolver resolver = new TaskSpecificationResolver();
    private final ObjectMapper json = new ObjectMapper();

    @Test void aGenerationCommandSurvivesUnrelatedNegationAndQuestions() {
        for (String goal : List.of("生成讲稿，不要公式", "生成讲稿，告诉我怎么学", "请生成一份概率论讲稿，不要公式，告诉我怎么学"))
            assertThat(resolver.resolve(goal, null).requiredArtifacts()).as(goal).containsExactly("SCRIPT_DRAFT");
    }

    @Test void instructionalQuestionsAndLocalNegationsDoNotRequireGeneration() {
        for (String goal : List.of("如何生成讲稿", "请告诉我如何生成课程", "不要生成，只回答", "不要生成讲稿，只回答如何学习", "我想了解怎么生成音频"))
            assertThat(resolver.resolve(goal, null).requiredArtifacts()).as(goal).isEmpty();
    }

    @Test void aLearningPlanDoesNotImplicitlyRequireAScript() {
        for (String goal : List.of("生成学习计划", "制定概率论学习计划", "生成课程计划", "请创建一个完整的学习计划"))
            assertThat(resolver.resolve(goal, null).requiredArtifacts()).as(goal).containsExactly("LEARNING_PLAN");
    }

    @Test void aNegatedSecondActionCannotBorrowTheFirstActionsVerb() {
        assertThat(resolver.resolve("生成学习计划但不要生成讲稿", null).requiredArtifacts()).containsExactly("LEARNING_PLAN");
    }

    @Test void naturalAudioRequestsDoNotRequireLearningPlans() {
        for (String goal : List.of("写一个英文故事", "制作今天的时政简报", "我想听一个侦探小说", "写一段机场对话", "Create an English story"))
            assertThat(resolver.resolve(goal, null).requiredArtifacts()).as(goal).containsExactly("SCRIPT_DRAFT");
        for (String question : List.of("如何写小说", "不要制作新闻简报", "怎么生成英文故事"))
            assertThat(resolver.resolve(question, null).requiredArtifacts()).as(question).isEmpty();
    }

    @Test void explicitApiRequirementsRemainRequiredEvenForAnOtherwiseOpenGoal() {
        var specification = resolver.resolve("告诉我怎么学", Set.of("SCRIPT_DRAFT", "REVIEW_REPORT"));
        assertThat(specification.requiredArtifacts()).containsExactlyInAnyOrder("SCRIPT_DRAFT", "REVIEW_REPORT");
        assertThat(specification.origin()).contains("USER_API");
        assertThat(specification.augment(List.of()).requiredArtifacts()).containsExactlyInAnyOrder("SCRIPT_DRAFT", "REVIEW_REPORT");
    }

    @Test void specificationCopiesRequirementsAndRejectsUnsupportedKinds() {
        Set<String> mutable = new HashSet<>(Set.of("LEARNING_PLAN"));
        var specification = new TaskSpecification(mutable, "USER_API"); mutable.clear();
        assertThat(specification.requiredArtifacts()).containsExactly("LEARNING_PLAN");
        assertThatThrownBy(() -> specification.requiredArtifacts().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> specification.augment(List.of("AUDIO"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void declarationOnlyAddsToUserRequirementsThenFreezesTheSavedContract() {
        var runs = mock(AgentRunStore.class);
        var declare = new DeclareTaskSpecificationTool(runs);
        var context = context(null);
        context.taskSpecification = resolver.resolve("生成讲稿", Set.of("SCRIPT_DRAFT"));
        declare.execute(json.valueToTree(Map.of("required_artifacts", List.of("REVIEW_REPORT"))), context);
        assertThat(context.taskSpecification.requiredArtifacts()).containsExactlyInAnyOrder("SCRIPT_DRAFT", "REVIEW_REPORT");
        assertThat(context.taskSpecificationDeclared).isTrue(); assertThat(context.taskSpecificationFrozen).isTrue();
        verify(runs).saveSpecification("run", context.taskSpecification.data());
        assertThatThrownBy(() -> declare.execute(json.valueToTree(Map.of("required_artifacts", List.of())), context))
                .hasMessageContaining("委派前声明");
        verify(runs, times(1)).saveSpecification(anyString(), any());
    }

    @Test void anEmptyDeclarationCannotDeleteExplicitApiRequirements() {
        var runs = mock(AgentRunStore.class); var context = context(null);
        context.taskSpecification = resolver.resolve("只回答", Set.of("SCRIPT_DRAFT"));
        new DeclareTaskSpecificationTool(runs).execute(json.valueToTree(Map.of("required_artifacts", List.of())), context);
        assertThat(context.taskSpecification.requiredArtifacts()).containsExactly("SCRIPT_DRAFT");
    }

    @Test void delegationStillWorksAfterDeclarationHasFrozenTheSpecification() {
        var runs = mock(AgentRunStore.class);
        AgentExecutionContext.Delegate delegate = mock(AgentExecutionContext.Delegate.class);
        var context = context(delegate);
        context.taskSpecification = resolver.resolve("生成讲稿", null);
        new DeclareTaskSpecificationTool(runs).execute(json.valueToTree(Map.of("required_artifacts", List.of())), context);
        when(delegate.run(context, "script_writer", "生成讲稿", List.of())).thenReturn(Map.of("status", "OK", "artifact_id", "a"));
        Object result = new DelegateAgentTool(mock(AgentRegistry.class), runs).execute(
                json.valueToTree(Map.of("agent_id", "script_writer", "task", "生成讲稿", "artifact_ids", List.of())), context);
        assertThat(json.valueToTree(result).path("artifact_id").asText()).isEqualTo("a");
        verify(delegate).run(context, "script_writer", "生成讲稿", List.of());
        verify(runs, times(2)).saveSpecification("run", context.taskSpecification.data());
    }

    @Test void anOpenGoalMustBeDeclaredBeforeTheFirstDelegation() {
        var runs = mock(AgentRunStore.class);
        AgentExecutionContext.Delegate delegate = mock(AgentExecutionContext.Delegate.class);
        var context = context(delegate); context.taskSpecification = resolver.resolve("帮我继续", null);
        assertThatThrownBy(() -> new DelegateAgentTool(mock(AgentRegistry.class), runs).execute(
                json.valueToTree(Map.of("agent_id", "script_writer", "task", "生成讲稿")), context)).hasMessageContaining("先声明");
        verifyNoInteractions(runs, delegate);
    }

    private AgentExecutionContext context(AgentExecutionContext.Delegate delegate) {
        return new AgentExecutionContext("run", "session", "supervisor", 1, 7,
                new AgentBudget(), new EvidenceLedger(), null, delegate, ignored -> {});
    }
}
