package com.bedouyu.audiorag.ai;
import com.bedouyu.audiorag.model.dto.AgentModelReply;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 原生 Function Calling：工具定义、assistant.tool_calls 和 tool_call_id 都遵循供应商协议。 */
public interface AgentModelGateway {
    boolean configured();
    AgentModelReply decide(List<Map<String, Object>> messages,
                           List<FunctionDefinition> tools, Consumer<String> contentToken);
    /** 新实现将运行剩余时限传到底层网络；旧 Mock/适配器保持兼容。 */
    default AgentModelReply decide(List<Map<String, Object>> messages, List<FunctionDefinition> tools,
                                   Consumer<String> contentToken, long timeoutMillis) {
        return decide(messages, tools, contentToken);
    }
    /**
     * 按用途选择模型：{@code "review"} 可在配置了复核模型时使用第二模型，
     * 其余一律走主模型。默认实现忽略该参数，兼容既有 Mock。
     */
    default AgentModelReply decide(List<Map<String, Object>> messages, List<FunctionDefinition> tools,
                                   Consumer<String> contentToken, long timeoutMillis, String modelTier) {
        return decide(messages, tools, contentToken, timeoutMillis);
    }
    /** Mock 可返回 empty；真实实现只暴露计数与策略，不包含消息正文或密钥。 */
    default Map<String, Object> budgetSnapshot(List<Map<String, Object>> messages,
                                              List<FunctionDefinition> tools) { return Map.of(); }
    /** 当前调用线程最近一次预算与供应商 usage，开始新请求时必须清空。 */
    default Map<String, Object> lastObservation() { return Map.of(); }
}
