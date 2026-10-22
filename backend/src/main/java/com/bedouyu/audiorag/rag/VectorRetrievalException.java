package com.bedouyu.audiorag.rag;

/** Only temporary transport/service failures may trigger lexical fallback. */
public class VectorRetrievalException extends IllegalStateException {
    public enum Kind {
        TRANSIENT, AUTHENTICATION, COLLECTION_MISSING, SCHEMA,
        EMBEDDING, DIMENSION_MISMATCH, REQUEST_REJECTED, PROTOCOL
    }

    private final Kind kind;

    public VectorRetrievalException(Kind kind, String message) {
        this(kind, message, null);
    }

    public VectorRetrievalException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = java.util.Objects.requireNonNull(kind);
    }

    public Kind kind() { return kind; }
    public boolean isTransient() { return kind == Kind.TRANSIENT; }
}
