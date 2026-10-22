package com.bedouyu.audiorag.core;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.core.guard.ReviewBindingGuard;
import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.service.AgentArtifactStore;
import com.bedouyu.audiorag.tools.agent.ArtifactReadTool;
import com.bedouyu.audiorag.tools.audio.ValidateScriptTool;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ReviewBindingGuardTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AgentArtifactStore store = mock(AgentArtifactStore.class);
    private final ReviewBindingGuard guard = new ReviewBindingGuard(store, json);
    private final ArtifactReadTool read = new ArtifactReadTool(store, json);
    private final ValidateScriptTool check = new ValidateScriptTool(store);
    private final String scriptA = "课程讲解需明确来源，逐步说明证据能够支持的结论，保留推断边界并用例子帮助理解。 [E1]";
    private final String scriptB = "第二份课程讲稿包含另一个观点，解释它的前提、具体论据和应用限制，并提示继续阅读原始来源。 [E1]";

    @Test void reviewRejectsTargetsOutsideTheExplicitWhitelist() {
        var context = context(Set.of("a"));
        artifact("b", scriptB);
        assertThatThrownBy(() -> guard.validate(report("b", scriptB, true, List.of()), context)).hasMessageContaining("显式传入");
        verifyNoInteractions(store);
    }

    @Test void rootContextAlsoNeedsAnExplicitReviewTargetWhitelist() {
        assertThatThrownBy(() -> guard.validate(report("a", scriptA, true, List.of()), context(null))).hasMessageContaining("显式传入");
    }

    @Test void currentImmutableSourceHashMustMatchTheReport() {
        var context = context(Set.of("a"));
        artifact("a", scriptA); readAllAndCheck("a", scriptA, context);
        assertThatThrownBy(() -> guard.validate(report("a", scriptB, true, List.of()), context)).hasMessageContaining("原文不一致");
    }

    @Test void partialReadingCannotApproveEvenAfterAPassingCheck() {
        var context = context(Set.of("a"));
        String longScript = scriptA.repeat(5); artifact("a", longScript);
        read.execute(args(Map.of("artifact_id", "a", "limit", 100)), context);
        check.execute(args(Map.of("artifact_id", "a", "content_hash", Hashing.sha256(longScript))), context);
        assertThatThrownBy(() -> guard.validate(report("a", longScript, true, List.of()), context)).hasMessageContaining("完整目标讲稿");
    }

    @Test void checkingACannotApproveBWhenBothHaveBeenRead() {
        var context = context(Set.of("a", "b"));
        artifact("a", scriptA); artifact("b", scriptB); readAllAndCheck("a", scriptA, context);
        read.execute(args(Map.of("artifact_id", "b")), context);
        assertThatThrownBy(() -> guard.validate(report("b", scriptB, true, List.of()), context)).hasMessageContaining("同一目标讲稿的本机检查");
    }

    @Test void reviewMustStateEveryCheckExplicitly() {
        var context = context(Set.of("a"));
        artifact("a", scriptA); readAllAndCheck("a", scriptA, context);
        String halfChecked = json.writeValueAsString(Map.of("reviewed_artifact_id", "a",
                "content_hash", Hashing.sha256(scriptA), "checks", Map.of("topic", true),
                "approved", true, "issues", List.of(), "reason", "只表态了一项"));

        assertThatThrownBy(() -> guard.validate(halfChecked, context)).hasMessageContaining("缺少布尔项");
    }

    @Test void aFailedCheckCannotBeReportedAsApproved() {
        var context = context(Set.of("a"));
        artifact("a", scriptA); readAllAndCheck("a", scriptA, context);
        var checks = new java.util.HashMap<String, Object>(allChecksMet());
        checks.put("ending", false);

        assertThatThrownBy(() -> guard.validate(report("a", scriptA, true, List.of(), checks), context))
                .hasMessageContaining("不能 approved=true");
    }

    @Test void rejectionWithARealFailedCheckRemainsAValidBoundReview() {
        var context = context(Set.of("a"));
        String source = "没有引用的课程讲稿说明一些观点，但并未提供对应来源，复核后需要补充关键事实的证据才可以使用。";
        artifact("a", source); readAllAndCheck("a", source, context);
        assertThatCode(() -> guard.validate(report("a", source, false, List.of("关键事实缺少来源")), context)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.validate(report("a", source, true, List.of()), context)).hasMessageContaining("本机检查失败");
    }

    @Test void approvalRequiresAnEmptyIssuesArrayAndValidScriptKind() {
        var context = context(Set.of("a"));
        artifact("a", scriptA); readAllAndCheck("a", scriptA, context);
        assertThatCode(() -> guard.validate(report("a", scriptA, true, List.of()), context)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.validate(report("a", scriptA, true, List.of("尚有问题")), context)).hasMessageContaining("仍有复核问题");
        when(store.get(1, 7, "a")).thenReturn(Map.of("kind", "RESEARCH_REPORT", "content", scriptA, "citations", citations()));
        assertThatThrownBy(() -> guard.validate(report("a", scriptA, false, List.of()), context)).hasMessageContaining("讲稿产物");
    }

    @Test void rejectedReviewsAlsoRequireTheTargetCheck() {
        var context = context(Set.of("a")); artifact("a", scriptA);
        read.execute(args(Map.of("artifact_id", "a")), context);
        assertThatThrownBy(() -> guard.validate(report("a", scriptA, false, List.of("证据不足")), context)).hasMessageContaining("本机检查");
    }

    private void readAllAndCheck(String id, String source, AgentExecutionContext context) {
        read.execute(args(Map.of("artifact_id", id)), context);
        check.execute(args(Map.of("artifact_id", id, "content_hash", Hashing.sha256(source))), context);
    }
    private void artifact(String id, String content) {
        when(store.get(1, 7, id)).thenReturn(Map.of("kind", "SCRIPT_DRAFT", "content", content, "status", "DRAFT", "citations", citations()));
    }
    private List<Map<String, Object>> citations() {
        return List.of(Map.of("id", "E1", "source_key", "old:key", "title", "来源", "source", "knowledge", "score", .9));
    }
    private String report(String id, String source, boolean approved, List<String> issues) {
        return report(id, source, approved, issues, allChecksMet());
    }
    private Map<String, Object> allChecksMet() {
        return Map.of("topic", true, "genre", true, "language", true, "ending", true,
                "length", true, "citations", true, "readable", true);
    }
    private String report(String id, String source, boolean approved, List<String> issues,
                          Map<String, Object> checks) {
        return json.writeValueAsString(Map.of("reviewed_artifact_id", id, "content_hash", Hashing.sha256(source),
                "checks", checks, "approved", approved, "issues", issues,
                "reason", "已核对目标全文和本机检查，按证据记录结论"));
    }
    private tools.jackson.databind.JsonNode args(Map<String, Object> value) { return json.valueToTree(value); }
    private AgentExecutionContext context(Set<String> ids) {
        return new AgentExecutionContext("run", "session", "review_agent", 1, 7,
                new AgentBudget(), new EvidenceLedger(), ids, null, ignored -> {});
    }
}
