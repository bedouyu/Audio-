package com.bedouyu.audiorag.core.loop;
import com.bedouyu.audiorag.tools.ToolRegistry;
import com.bedouyu.audiorag.tools.research.WebSearchException;
import com.bedouyu.audiorag.rag.QdrantService.SearchHit;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** 有界检索工作流：本地检索 → 证据评估 → 可选网络补充。单次最多两次工具调用。 */
@Component
public class AudioRetrievalLoop {
    private final ToolRegistry registry;
    public AudioRetrievalLoop(ToolRegistry registry) { this.registry = registry; }
    public List<SearchHit> run(long projectId, String query, int limit, Consumer<Map<String, Object>> event) {
        List<SearchHit> hits = new ArrayList<>(registry.require("knowledge_search").execute(projectId, query, limit));
        event.accept(Map.of("tool", "knowledge_search", "hit_count", hits.size()));
        if (hits.isEmpty() || hits.get(0).score() < 0.62 || !hasLexicalSignal(query, hits.get(0).content())) {
            try {
                List<SearchHit> web = registry.require("web_search").execute(projectId, query, 3);
                event.accept(Map.of("tool", "web_search", "hit_count", web.size(), "status", web.isEmpty() ? "NO_RESULTS" : "OK"));
                hits.addAll(web);
            } catch (WebSearchException unavailable) {
                event.accept(Map.of("tool", "web_search", "status", "ERROR", "error_code", unavailable.kind().name(), "message", unavailable.getMessage()));
                if (hits.isEmpty()) throw unavailable;
                // 有本机证据时继续；网络故障仍明确记录，不冒充网络零召回。
            }
        }
        return List.copyOf(hits);
    }
    private boolean hasLexicalSignal(String query, String content) {
        String text = content.toLowerCase(Locale.ROOT);
        var words = java.util.regex.Pattern.compile("[a-z0-9_]{2,}").matcher(query.toLowerCase(Locale.ROOT));
        boolean latin = false;
        while (words.find()) { latin = true; if (text.contains(words.group())) return true; }
        if (latin) return false;
        String chinese = query.replaceAll("[^\\u4e00-\\u9fff]", "");
        for (int i = 0; i + 2 <= chinese.length(); i++) if (text.contains(chinese.substring(i, i + 2))) return true;
        return false;
    }
}
