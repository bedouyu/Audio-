package com.bedouyu.audiorag.tools;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.*;

/** 校验本项目使用的 JSON Schema 子集；不能由模型扩大参数范围或添加项目 ID。 */
@Component
public class ToolArgumentValidator {
    @SuppressWarnings("unchecked")
    public void validate(JsonNode value, Map<String, Object> schema) {
        String type = String.valueOf(schema.get("type"));
        boolean valid = switch (type) {
            case "object" -> value.isObject(); case "array" -> value.isArray();
            case "string" -> value.isTextual(); case "integer" -> value.isIntegralNumber();
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("工具参数类型不符：" + type);
        if ("object".equals(type)) {
            var properties = (Map<String, Object>) schema.get("properties");
            for (String required : (List<String>) schema.getOrDefault("required", List.of()))
                if (!value.has(required)) throw new IllegalArgumentException("缺少工具参数：" + required);
            for (var field : value.properties()) {
                if (!properties.containsKey(field.getKey())) throw new IllegalArgumentException("不允许的工具参数：" + field.getKey());
                validate(field.getValue(), (Map<String, Object>) properties.get(field.getKey()));
            }
        }
        if ("string".equals(type)) {
            int length = value.asText().length();
            if (length < ((Number) schema.getOrDefault("minLength", 0)).intValue()
                    || length > ((Number) schema.getOrDefault("maxLength", 8000)).intValue())
                throw new IllegalArgumentException("工具字符串长度超限");
            if (schema.containsKey("enum") && !((List<String>) schema.get("enum")).contains(value.asText()))
                throw new IllegalArgumentException("工具参数不在可选清单中");
        }
        if ("integer".equals(type)) {
            if (!value.canConvertToInt()) throw new IllegalArgumentException("工具整数超限");
            int number = value.asInt();
            if (number < ((Number) schema.getOrDefault("minimum", Integer.MIN_VALUE)).intValue()
                    || number > ((Number) schema.getOrDefault("maximum", Integer.MAX_VALUE)).intValue())
                throw new IllegalArgumentException("工具整数超限");
        }
        if ("array".equals(type)) {
            if (value.size() > ((Number) schema.getOrDefault("maxItems", 8)).intValue())
                throw new IllegalArgumentException("工具数组过长");
            for (JsonNode item : value) validate(item, (Map<String, Object>) schema.get("items"));
        }
    }
}
