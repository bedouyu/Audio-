package com.bedouyu.audiorag.memory.strategy;

import java.util.Objects;

/** A persisted message identity and its position in the original conversation. */
public record MemoryMessage(String id, String role, String content, int ordinal, String turnId) {
    public MemoryMessage(String id, String role, String content, int ordinal) {
        this(id, role, content, ordinal, null);
    }

    public MemoryMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        if (id.isBlank() || ordinal < 0) throw new IllegalArgumentException("Invalid message identity or ordinal");
        if (turnId != null && turnId.isBlank()) throw new IllegalArgumentException("Blank turn identity");
    }
}
