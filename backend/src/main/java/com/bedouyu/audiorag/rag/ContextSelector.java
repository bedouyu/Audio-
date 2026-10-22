package com.bedouyu.audiorag.rag;

import com.bedouyu.audiorag.rag.QdrantService.SearchHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ContextSelector {
    public List<SearchHit> select(String query, List<SearchHit> candidates, int limit) {
        List<SearchHit> ranked = candidates.stream()
                .sorted(Comparator.comparingDouble((SearchHit hit) -> hit.score() + lexicalBoost(query, hit.content())).reversed())
                .toList();
        List<SearchHit> selected = new ArrayList<>();
        Map<String, Integer> sourceCounts = new HashMap<>();
        for (SearchHit candidate : ranked) {
            if (sourceCounts.getOrDefault(candidate.source(), 0) >= 2 || nearDuplicate(candidate, selected)) {
                continue;
            }
            selected.add(candidate);
            sourceCounts.merge(candidate.source(), 1, Integer::sum);
            if (selected.size() == limit) {
                break;
            }
        }
        return selected;
    }

    private double lexicalBoost(String query, String content) {
        String normalized = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String text = content.toLowerCase(Locale.ROOT);
        int matches = 0;
        for (String term : normalized.split("[^a-z0-9_\\u4e00-\\u9fff]+")) {
            if (term.length() >= 2 && text.contains(term)) {
                matches++;
            }
        }
        return Math.min(0.08, matches * 0.02);
    }

    private boolean nearDuplicate(SearchHit candidate, List<SearchHit> selected) {
        Set<String> tokens = shingles(candidate.content());
        for (SearchHit existing : selected) {
            Set<String> other = shingles(existing.content());
            Set<String> intersection = new HashSet<>(tokens);
            intersection.retainAll(other);
            int union = tokens.size() + other.size() - intersection.size();
            if (union > 0 && (double) intersection.size() / union >= 0.72) {
                return true;
            }
        }
        return false;
    }

    private Set<String> shingles(String content) {
        String normalized = content.replaceAll("\\s+", "").trim();
        Set<String> result = new HashSet<>();
        for (int index = 0; index + 2 <= normalized.length(); index++) {
            result.add(normalized.substring(index, index + 2));
        }
        return result;
    }
}
