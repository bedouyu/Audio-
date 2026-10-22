package com.bedouyu.audiorag.tools.research;
import com.bedouyu.audiorag.tools.AgentTool;
import com.bedouyu.audiorag.rag.QdrantService;
import com.bedouyu.audiorag.rag.ContextSelector;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class KnowledgeSearchTool implements AgentTool {
    private final QdrantService qdrant;
    private final ContextSelector selector;
    public KnowledgeSearchTool(QdrantService qdrant, ContextSelector selector) {
        this.qdrant = qdrant; this.selector = selector;
    }
    public String id() { return "knowledge_search"; }
    public List<QdrantService.SearchHit> execute(long projectId, String query, int limit) {
        return selector.select(query, qdrant.search(query, 20, projectId), Math.min(limit, 6));
    }
}
