package com.bedouyu.audiorag.tools.research;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.rag.AdaptiveKnowledgeRetriever;
import com.bedouyu.audiorag.tools.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** 主模型通过原生 Function Calling 选择策略；服务端负责范围、预算、降级和真实执行。 */
@Component
public class AdaptiveKnowledgeSearchTool implements FunctionTool {
    private final AdaptiveKnowledgeRetriever retrieval;
    public AdaptiveKnowledgeSearchTool(AdaptiveKnowledgeRetriever retrieval) { this.retrieval = retrieval; }
    public FunctionDefinition definition() {
        return new FunctionDefinition("knowledge_search",
                "检索当前项目资料。先 knowledge_catalog 看文档类型。精确名称/编号选bm25，同义改写选dense，混合问题选hybrid；"
                + "单点事实选top_k，依赖前后文选neighbors，跨连续段解释/流程选rse_rank_proxy（无校准重排器的实验版）。"
                + "返回实际策略和降级说明；不要将分数当成事实置信概率。",
                ToolSchemas.object(Map.of("query", ToolSchemas.string(1000), "limit", ToolSchemas.integer(1, 6),
                        "retrieval_strategy", choice("dense", "bm25", "hybrid"),
                        "context_strategy", choice("top_k", "neighbors", "rse_rank_proxy"),
                        "context_token_budget", ToolSchemas.integer(256, 2048),
                        "document_ids", ToolSchemas.strings(20), "strategy_reason", ToolSchemas.string(300)), "query"));
    }
    private static Map<String, Object> choice(String... values) {
        return Map.of("type", "string", "enum", List.of(values));
    }
    public Object execute(JsonNode args, AgentExecutionContext context) {
        String strategy = args.path("retrieval_strategy").asText("hybrid");
        String assembly = args.path("context_strategy").asText("top_k");
        List<String> documents = new ArrayList<>();
        args.path("document_ids").forEach(id -> documents.add(id.asText()));
        var result = retrieval.search(context.projectId, args.path("query").asText(), strategy, assembly,
                args.path("context_token_budget").asInt(1024), args.path("limit").asInt(4), documents);
        // 完整证据段已受总预算保护，不再按单片1000字截断破坏RSE。
        context.assertActive();
        var evidence = context.evidence.register(result.hits(), 4800);
        Map<String, Object> observation = new LinkedHashMap<>(Map.of("status", evidence.isEmpty() ? "NO_RESULTS" : "OK", "evidence", evidence,
                "hit_count", evidence.size(), "requested_retrieval", strategy, "requested_context", assembly,
                "actual_retrieval", result.retrieval(), "actual_context", result.assembly(),
                "context_tokens_bge", result.contextTokens(), "candidate_count", result.candidateCount(),
                "warnings", result.warnings()));
        observation.put("strategy_reason", args.path("strategy_reason").asText("未说明；使用调用参数或默认 hybrid/top_k"));
        observation.put("bm25_index_cache", result.indexCacheStatus());
        observation.put("index_revision", result.indexRevision());
        return observation;
    }
}
