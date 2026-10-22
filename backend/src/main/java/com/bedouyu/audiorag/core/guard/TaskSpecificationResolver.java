package com.bedouyu.audiorag.core.guard;

import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.*;

/**
 * 保守提取明确生成命令的最低要求。否定/问句按动作局部判断，不能因“不要公式”跳过生成检查。
 * 不是自然语言语义完成证明；开放式目标可由declare_task_specification只增不减地补充契约。
 */
@Component
public class TaskSpecificationResolver {
    private static final Map<String, String> TARGETS = Map.of(
            "LEARNING_PLAN", "学习计划|课程计划", "SCRIPT_DRAFT", "课程(?!计划)|讲稿|音频|播客|教程|故事|小说|简报|对话|短文|章节|有声书|\\baudio\\b|\\bpodcast\\b|\\bstory\\b|\\bfiction\\b|\\bdialogue\\b|\\bbriefing\\b",
            "RESEARCH_REPORT", "研究报告|资料报告");
    public TaskSpecification resolve(String goal, Collection<String> explicitKinds) {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("任务目标不能为空");
        Set<String> required = new TreeSet<>(explicitKinds == null ? Set.of() : explicitKinds);
        for (String clause : goal.split("[，,。；;！!？?\\n]")) {
            for (var target : TARGETS.entrySet()) {
                var action = Pattern.compile("(生成|编写|制定|建立|制作|写(?:一份|一个|一篇|出)?|创建|规划|讲|朗读|想听|想要听|听一个|听一段|听一篇|write|create|generate|tell|narrate)\\s*(?:[^，,。；;\\n]{0,32}?)(" + target.getValue() + ")", Pattern.CASE_INSENSITIVE).matcher(clause);
                while (action.find()) if (command(clause, action.start())
                        && !clause.substring(action.end(1), action.start(2)).matches("(?s).*(不要|无需|不必|不用|不需要|别)\\s*(生成|编写|制作|创建|写)?\\s*$")) required.add(target.getKey());
            }
            var review = Pattern.compile("复核|审查|核查").matcher(clause);
            while (review.find()) if (command(clause, review.start()) && !clause.strip().matches("^(复核|审查|核查)(报告)?(是什么|有什么|有哪些|如何|怎么|为什么).*$"))
                required.add("REVIEW_REPORT");
        }
        // “生成学习计划”不隐式要求讲稿；只有同一命令确实出现课程/讲稿等才添加。
        return new TaskSpecification(required, explicitKinds == null || explicitKinds.isEmpty() ? "REQUEST_PATTERN_V2" : "USER_API+REQUEST_PATTERN_V2");
    }
    private boolean command(String clause, int actionStart) {
        String before = clause.substring(0, actionStart).strip();
        if (before.matches("(?s).*(不要|无需|不必|不用|别|禁止|不能|不需要|不想|暂不|不做|不)(?:再|直接|立即)?\\s*$")) return false;
        if (before.matches("(?s).*(如何|怎么|怎样|是否|能否|可以|解释|讲解|介绍|说明)(?:才能|能够|可以|去)?\\s*$")) return false;
        if (before.matches("(?is).*(how\\s+to|how\\s+can\\s+(?:i|we)|do\\s+not|don't|please\\s+don't)\\s*$")) return false;
        return true;
    }
}
