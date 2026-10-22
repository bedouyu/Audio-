package com.bedouyu.audiorag.model.dto;
import java.util.Map;

public record FunctionDefinition(String name, String description, Map<String, Object> parameters) {
    public Map<String, Object> wire() {
        return Map.of("type", "function", "function", Map.of(
                "name", name, "description", description, "parameters", parameters));
    }
}
