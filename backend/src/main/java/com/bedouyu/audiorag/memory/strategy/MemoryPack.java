package com.bedouyu.audiorag.memory.strategy;

import com.bedouyu.audiorag.model.dto.ModelMessage;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The model-visible, auditable memory envelope. The budget includes its JSON metadata. */
public record MemoryPack(String strategy, List<MemoryFragment> fragments,
                         int budgetChars, List<String> warnings) {
    public MemoryPack {
        Objects.requireNonNull(strategy, "strategy");
        fragments = List.copyOf(fragments);
        warnings = List.copyOf(warnings);
    }

    public Map<String, Object> modelData() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", strategy);
        result.put("fragments", fragments.stream().map(fragment -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source_id", fragment.sourceId());
            item.put("role", fragment.role());
            item.put("start", fragment.start());
            item.put("end", fragment.end());
            item.put("text", fragment.text());
            item.put("kind", fragment.kind());
            item.put("ordinal", fragment.ordinal());
            item.put("turn_id", fragment.turnId());
            return item;
        }).toList());
        result.put("warnings", warnings);
        result.put("budget_chars", budgetChars);
        return result;
    }

    public int characters(ObjectMapper json) {
        return Objects.requireNonNull(json, "json").writeValueAsString(modelData()).length();
    }

    /** Compatibility view; the formal model input is modelData(), which carries provenance. */
    public List<ModelMessage> messages() {
        return fragments.stream().map(fragment -> new ModelMessage(fragment.role(), fragment.text())).toList();
    }
}
