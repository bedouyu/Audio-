package com.bedouyu.audiorag.tools.research;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.model.dto.FunctionDefinition;
import com.bedouyu.audiorag.rag.index.KnowledgeIndexStore;
import com.bedouyu.audiorag.tools.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** 类型是文件格式/来源，不是声称模型已经识别了文档语义类型。目录不包含全文。 */
@Component
public class KnowledgeCatalogTool implements FunctionTool {
    private final KnowledgeIndexStore index;
    public KnowledgeCatalogTool(KnowledgeIndexStore index) { this.index = index; }
    public FunctionDefinition definition() {
        return new FunctionDefinition("knowledge_catalog", "查看当前项目新版知识库文档类型及ID，为选择检索策略提供依据；分页，不发送全文。",
                ToolSchemas.object(Map.of("offset", ToolSchemas.integer(0, 1000), "limit", ToolSchemas.integer(1, 20))));
    }
    public Object execute(JsonNode args, AgentExecutionContext context) {
        var docs = index.documents(context.projectId);
        int offset = args.path("offset").asInt(0), limit = args.path("limit").asInt(10);
        var items = docs.stream().skip(offset).limit(limit).map(doc -> Map.of(
                "document_id", doc.getId(), "title", doc.getTitle(), "document_type", doc.getDocumentType(),
                "characters", doc.getText().length(), "index_version", doc.getIndexVersion())).toList();
        return Map.of("documents", items, "total", docs.size(), "has_more", offset + items.size() < docs.size(),
                "retrieval_strategies", List.of("dense", "bm25", "hybrid"),
                "context_strategies", List.of("top_k", "neighbors", "rse_rank_proxy"),
                "legacy_note", "这里只列新版结构化索引；旧文档由knowledge_search显式兼容/降级。");
    }
}
