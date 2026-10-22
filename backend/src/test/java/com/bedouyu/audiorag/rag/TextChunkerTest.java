package com.bedouyu.audiorag.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextChunkerTest {
    private final TextChunker chunker = new TextChunker();

    @Test
    void keepsShortDocumentAsOneChunk() {
        assertThat(chunker.split("第一段。\n第二段。"))
                .containsExactly("第一段。\n第二段。");
    }

    @Test
    void splitsLongDocumentWithOverlap() {
        String text = "知识库内容。".repeat(250);
        List<String> chunks = chunker.split(text);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allMatch(chunk -> !chunk.isBlank() && chunk.length() <= 900);
        assertThat(chunks.get(0).substring(chunks.get(0).length() - 20))
                .isSubstringOf(chunks.get(1));
    }

    @Test
    void ignoresBlankContent() {
        assertThat(chunker.split("  \r\n ")).isEmpty();
    }
}
