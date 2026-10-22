package com.bedouyu.audiorag.tools;
import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import tools.jackson.databind.JsonNode;

public interface FunctionTool {
    FunctionDefinition definition();
    /** 持久化任务规格或写入外部系统的工具必须显式声明；超时后整轮停止并等待核查。 */
    default boolean hasSideEffects() { return false; }
    Object execute(JsonNode arguments, AgentExecutionContext context);
}
