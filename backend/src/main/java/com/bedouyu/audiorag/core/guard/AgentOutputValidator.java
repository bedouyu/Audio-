package com.bedouyu.audiorag.core.guard;

import com.bedouyu.audiorag.agent.AgentProfile;
import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.service.AgentArtifactStore;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import com.bedouyu.audiorag.service.content.AudioIntent;

@Component
public class AgentOutputValidator {
    private final ObjectMapper json;
    private final AgentArtifactStore artifacts;
    private final ReviewBindingGuard reviews;
    @Autowired
    public AgentOutputValidator(ObjectMapper json, AgentArtifactStore artifacts, ReviewBindingGuard reviews) {
        this.json = json; this.artifacts = artifacts; this.reviews = reviews;
    }
    public AgentOutputValidator(ObjectMapper json, AgentArtifactStore artifacts) {
        this(json, artifacts, new ReviewBindingGuard(artifacts, json));
    }
    public void validate(AgentProfile profile, String goal, String content,
                         Set<String> successfulTools, AgentExecutionContext context) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("模型未返回最终内容");
        var unknown = context.evidence.unknownCitations(content);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("引用了尚未检索到的证据：" + unknown);
        var intent = AudioIntent.resolve(goal, 5);
        if (intent.freshEvidenceRequired() && !context.evidence.hasFreshNewsEvidence(intent.fromDate(), intent.throughDate()))
            throw new IllegalArgumentException("时政与最新消息必须有本次联网取得且能核对发布日期的新闻证据；当前缺少 " + intent.timeScope() + " 的有效来源，不能用本机资料、旧产物或模型常识代替");
        if ("study_planner".equals(profile.id()) && !successfulTools.contains("project_context"))
            throw new IllegalArgumentException("学习规划必须先检查当前项目和已有学习进度");
        if ("research_agent".equals(profile.id()) && Collections.disjoint(successfulTools, Set.of("knowledge_search", "web_search")))
            throw new IllegalArgumentException("研究报告必须先执行真实检索");
        if ("review_agent".equals(profile.id()) && !successfulTools.contains("validate_script"))
            throw new IllegalArgumentException("复核必须先检查实际讲稿产物");
        if ("review_agent".equals(profile.id()) && !successfulTools.contains("read_artifact"))
            throw new IllegalArgumentException("复核必须先阅读实际讲稿");
        if ("script_writer".equals(profile.id()) && context.readableArtifacts != null
                && !context.readableArtifacts.isEmpty() && !successfulTools.contains("read_artifact"))
            throw new IllegalArgumentException("讲稿编写必须读取传入的资料产物");
        if ("LEARNING_PLAN".equals(profile.artifactKind())) validatePlan(content);
        if ("REVIEW_REPORT".equals(profile.artifactKind())) {
            var root = json.readTree(content);
            if (!root.path("approved").isBoolean() || !root.path("issues").isArray()
                    || root.path("issues").size() > 20 || root.path("reason").asText("").isBlank())
                throw new IllegalArgumentException("复核报告必须是 approved 布尔值、issues 数组和 reason 的 JSON 对象");
            reviews.validate(content, context);
        }
        if ("SCRIPT_DRAFT".equals(profile.artifactKind()) && (content.length() < 40 || content.length() > 24000))
            throw new IllegalArgumentException("讲稿长度应为 40—24000 字符");
        if ("supervisor".equals(profile.id())) {
            var outputs = artifacts.forRun(context.userId, context.projectId, context.runId);
            for (String required : context.taskSpecification.requiredArtifacts())
                if (outputs.stream().noneMatch(item -> required.equals(item.get("kind"))))
                    throw new IllegalArgumentException("最低任务规格未完成，缺少真实产物：" + required + "；不能只用文字声称完成");
        }
    }
    public void validatePlan(String content) {
        var root = json.readTree(content);
        var points = root.path("points");
        if (!points.isArray() || points.size() < 3 || points.size() > 12)
            throw new IllegalArgumentException("学习计划应返回 points 数组，包含 3—12 个知识点");
        Set<String> titles = new HashSet<>();
        for (var point : points) {
            String title = point.path("title").asText("").strip();
            if (title.isEmpty() || title.length() > 300 || !titles.add(title)
                    || point.path("description").asText("").length() > 1000)
                throw new IllegalArgumentException("知识点标题应唯一且不超过 300 字符");
        }
    }
}
