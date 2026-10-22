package com.bedouyu.audiorag.model.dto;
import java.util.Map;

public record FunctionCall(String id, String name, String arguments) {
    public Map<String, Object> wire() {
        return Map.of("id", id, "type", "function",
                "function", Map.of("name", name, "arguments", arguments));
    }
}
