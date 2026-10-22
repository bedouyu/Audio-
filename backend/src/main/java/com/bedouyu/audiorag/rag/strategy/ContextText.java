package com.bedouyu.audiorag.rag.strategy;
/** 所有方法使用同样的输出格式，标题、引用编号和分隔符也占预算。 */
public final class ContextText {
    private ContextText() { }
    public static String render(String documentId, String text) { return "[" + documentId + "]\n" + text + "\n\n"; }
}
