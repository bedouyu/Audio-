package com.bedouyu.audiorag.core.guard;

import java.util.*;

/** 独立于模型最终回答的最低完成契约；只约束草稿产物，不授予写入权限。 */
public record TaskSpecification(Set<String> requiredArtifacts, String origin) {
    public static final Set<String> ALLOWED = Set.of("LEARNING_PLAN", "RESEARCH_REPORT", "SCRIPT_DRAFT", "REVIEW_REPORT");
    public TaskSpecification {
        requiredArtifacts = Collections.unmodifiableSet(new TreeSet<>(requiredArtifacts));
        if (!ALLOWED.containsAll(requiredArtifacts)) throw new IllegalArgumentException("不支持的任务产物类型");
        if (origin == null || origin.isBlank()) throw new IllegalArgumentException("任务规格必须标明来源");
    }
    public TaskSpecification augment(Collection<String> added) {
        Set<String> union = new TreeSet<>(requiredArtifacts); union.addAll(added);
        return new TaskSpecification(union, origin + "+MODEL_DECLARATION");
    }
    public Map<String, Object> data() { return Map.of("required_artifacts", requiredArtifacts, "origin", origin); }
}
