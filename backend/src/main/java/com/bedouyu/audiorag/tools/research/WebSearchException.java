package com.bedouyu.audiorag.tools.research;

/** Search failure is different from a valid response containing no results. No response body is exposed. */
public class WebSearchException extends IllegalStateException {
    public enum Kind { TRANSIENT, REQUEST_REJECTED, PROTOCOL }
    private final Kind kind;
    public WebSearchException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
    public Kind kind() { return kind; }
}
