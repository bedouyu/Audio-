package com.bedouyu.audiorag.agent;

import com.bedouyu.audiorag.ai.AgentModelGateway;
import com.bedouyu.audiorag.model.dto.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** 仅测试协议与编排；这些确定性输出不得用来宣称真实模型质量或路由准确率。 */
public class ScenarioAgentGateway implements AgentModelGateway {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    public final List<List<Map<String, Object>>> requests = Collections.synchronizedList(new ArrayList<>());
    public boolean configured() { return true; }
    public AgentModelReply decide(List<Map<String, Object>> messages, List<FunctionDefinition> definitions, Consumer<String> token) {
        requests.add(messages);
        String role = String.valueOf(messages.get(0).get("content")).split("\n")[0].substring("AGENT_ID=".length());
        List<JsonNode> observations = messages.stream().filter(m -> "tool".equals(m.get("role")))
                .map(m -> json.readTree(String.valueOf(m.get("content")))).toList();
        if ("supervisor".equals(role)) {
            String goal = String.valueOf(messages.stream().filter(m -> "user".equals(m.get("role")))
                    .filter(m -> String.valueOf(m.get("content")).startsWith("当前任务："))
                    .findFirst().orElseThrow().get("content"));
            if (!goal.contains("生成")) {
                if (observations.isEmpty()) return call("knowledge_search", Map.of("query", "概率", "limit", 3));
                boolean evidence = observations.stream().anyMatch(o -> o.path("evidence").size() > 0);
                return finish(evidence ? "概率论研究随机事件的规律，依据本机资料 [E1]。" : "未检索到相关证据，请补充项目资料。", token);
            }
            Map<String, String> ids = new HashMap<>();
            observations.forEach(o -> { if (o.has("agent_id")) ids.put(o.path("agent_id").asText(), o.path("artifact_id").asText()); });
            if (!ids.containsKey("study_planner")) return delegate("study_planner", "为概率论课程建立学习计划", List.of());
            if (!ids.containsKey("research_agent")) return delegate("research_agent", "检索概率论知识与适用条件", List.of());
            if (!ids.containsKey("script_writer")) return delegate("script_writer", "依据研究和规划生成 5 分钟讲稿",
                    List.of(ids.get("research_agent"), ids.get("study_planner")));
            if (!ids.containsKey("review_agent")) return delegate("review_agent", "复核讲稿与证据",
                    List.of(ids.get("script_writer"), ids.get("research_agent")));
            return finish("已生成学习计划、研究报告与课程讲稿，复核结果已保存；请确认后合成音频 [E1]。", token);
        }
        if ("study_planner".equals(role)) {
            if (observations.isEmpty()) return call("project_context", Map.of());
            return finish("{\"title\":\"概率论学习计划\",\"points\":[{\"title\":\"随机事件\",\"description\":\"定义与例子\"},{\"title\":\"概率测度\",\"description\":\"公理与边界\"},{\"title\":\"条件概率\",\"description\":\"条件与应用\"}]}", token);
        }
        if ("research_agent".equals(role)) {
            if (observations.isEmpty()) return call("knowledge_search", Map.of("query", "概率", "limit", 3));
            return finish("研究报告：概率论研究随机事件的规律 [E1]。应从事件、测度与条件逐步讲解，不能忽略定义的适用边界。", token);
        }
        List<String> inputs = inputIds(messages);
        Set<String> read = toolArguments(messages, "read_artifact", "artifact_id");
        for (String id : inputs) if (!read.contains(id)) return call("read_artifact", Map.of("artifact_id", id, "limit", 4000));
        if ("script_writer".equals(role)) {
            if (toolArguments(messages, "estimate_duration", "text").isEmpty())
                return call("estimate_duration", Map.of("text", draft(), "target_minutes", 5));
            return finish(draft(), token);
        }
        if ("review_agent".equals(role)) {
            var target = observations.stream().filter(o -> "SCRIPT_DRAFT".equals(o.path("kind").asText()))
                    .findFirst().orElseThrow();
            if (toolArguments(messages, "validate_script", "artifact_id").isEmpty())
                return call("validate_script", Map.of("artifact_id", target.path("artifact_id").asText(),
                        "content_hash", target.path("content_hash").asText()));
            return finish(json.writeValueAsString(Map.of("reviewed_artifact_id", target.path("artifact_id").asText(),
                    "content_hash", target.path("content_hash").asText(), "approved", true, "issues", List.of(),
                    "reason", "已读取同一版本讲稿与研究证据，引用编号可核查；实际时长尚未测量。")), token);
        }
        throw new IllegalStateException("测试 Agent 未定义：" + role);
    }
    private AgentModelReply delegate(String role, String task, List<String> ids) {
        return call("delegate_agent", Map.of("agent_id", role, "task", task, "artifact_ids", ids));
    }
    private AgentModelReply call(String name, Map<String, Object> args) {
        return new AgentModelReply("", "", List.of(new FunctionCall("call_" + calls.incrementAndGet(), name,
                json.writeValueAsString(args))), "tool_calls");
    }
    private AgentModelReply finish(String text, Consumer<String> token) {
        int middle = text.length() / 2; token.accept(text.substring(0, middle)); token.accept(text.substring(middle));
        return new AgentModelReply(text, "", List.of(), "stop");
    }
    private String draft() {
        return "你好，这一节介绍概率论的基本问题。概率论研究随机事件的规律 [E1]。\n\n"
                + "首先明确事件的含义，再认识概率测度及其适用条件。学习时应把定义与具体例子联系起来 [E1]。\n\n"
                + "最后回到资料核对条件，不把直觉理解替代严格定义。以上是待审核的课程讲稿。";
    }
    private List<String> inputIds(List<Map<String, Object>> messages) {
        String task = String.valueOf(messages.get(1).get("content"));
        var matcher = java.util.regex.Pattern.compile("[0-9a-f-]{36}").matcher(task);
        List<String> result = new ArrayList<>(); while (matcher.find()) result.add(matcher.group()); return result;
    }
    private Set<String> toolArguments(List<Map<String, Object>> messages, String tool, String parameter) {
        Set<String> result = new HashSet<>();
        for (var message : messages) {
            if (!message.containsKey("tool_calls")) continue;
            var node = json.valueToTree(message.get("tool_calls"));
            for (var call : node) if (tool.equals(call.path("function").path("name").asText()))
                result.add(json.readTree(call.path("function").path("arguments").asText()).path(parameter).asText());
        }
        return result;
    }
}
