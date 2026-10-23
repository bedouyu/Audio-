package com.bedouyu.audiorag.tools;
import java.util.*;

public final class ToolSchemas {
    private ToolSchemas() { }
    public static Map<String, Object> object(Map<String, Object> properties, String... required) {
        return Map.of("type", "object", "properties", properties,
                "required", List.of(required), "additionalProperties", false);
    }
    public static Map<String, Object> string(int maximum) { return Map.of("type", "string", "minLength", 1, "maxLength", maximum); }
    public static Map<String, Object> integer(int min, int max) { return Map.of("type", "integer", "minimum", min, "maximum", max); }
    public static Map<String, Object> bool(String description) {
        return Map.of("type", "boolean", "description", description);
    }
    public static Map<String, Object> strings(int maximum) {
        return Map.of("type", "array", "items", string(100), "maxItems", maximum);
    }
}
