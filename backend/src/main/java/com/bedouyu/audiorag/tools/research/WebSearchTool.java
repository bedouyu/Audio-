package com.bedouyu.audiorag.tools.research;
import com.bedouyu.audiorag.tools.AgentTool;
import com.bedouyu.audiorag.rag.QdrantService.SearchHit;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class WebSearchTool implements AgentTool {
    private final WebSearchService search;
    public WebSearchTool(WebSearchService search) { this.search = search; }
    public String id() { return "web_search"; }
    public List<SearchHit> execute(long projectId, String query, int limit) { return search.search(query, Math.min(limit, 3)); }
}
