package com.bedouyu.audiorag.mapper;

import com.bedouyu.audiorag.model.entity.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;

/** 保持旧版 snake_case API 的兼容性；实体与 API 表示分开。 */
@Component
public class AudioApiMapper {
    private final ObjectMapper json;
    public AudioApiMapper(ObjectMapper json) { this.json = json; }
    public Map<String, Object> user(UserAccount value) {
        return Map.of("id", value.getId(), "username", value.getUsername());
    }
    public Map<String, Object> project(LearningProject value) {
        return Map.of("id", value.getId(), "topic", value.getTopic(), "title", value.getTitle(),
                "created_at", value.getCreatedAt(), "updated_at", value.getUpdatedAt());
    }
    public Map<String, Object> record(ProjectRecord value) {
        return Map.of("id", value.getId(), "record_type", value.getRecordType(),
                "title", value.getTitle(), "content", value.getContent(),
                "metadata", json.readValue(value.getMetadata(), Map.class),
                "created_at", value.getCreatedAt());
    }
    public Map<String, Object> event(AgentEvent value) {
        return Map.of("step", value.getStep(), "event_type", value.getEventType(),
                "name", value.getName(), "payload", json.readValue(value.getPayload(), Map.class));
    }
    public Map<String, Object> session(ChatSession value) {
        return Map.of("id", value.getId(), "title", value.getTitle(),
                "project_id", value.getProjectId(), "updated_at", value.getUpdatedAt());
    }
    public String json(Object value) { return json.writeValueAsString(value); }
}
