package com.bedouyu.audiorag.tools;
import com.bedouyu.audiorag.rag.QdrantService.SearchHit;
import java.util.List;

public interface AgentTool {
    String id();
    List<SearchHit> execute(long projectId, String query, int limit);
}
