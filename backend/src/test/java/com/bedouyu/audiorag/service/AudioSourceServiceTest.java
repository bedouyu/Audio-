package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.model.entity.KnowledgeIndexDocument;
import com.bedouyu.audiorag.rag.index.KnowledgeIndexStore;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AudioSourceServiceTest {
    private final AudioRagRepository repository = mock(AudioRagRepository.class);
    private final KnowledgeIndexStore index = mock(KnowledgeIndexStore.class);
    private final AudioSourceService service = new AudioSourceService(repository, index);

    @BeforeEach void setup() { when(repository.ownsProject(7, 9)).thenReturn(true); }

    @Test void soleSourceUsesExactCanonicalTextIncludingWhitespaceAndNeverARecordExcerpt() {
        String original = "  第一章 开始\r\n\r\n一字也不修改。  \r\n";
        var document = document("小说", original);
        when(index.documents(9)).thenReturn(List.of(document));
        var result = service.resolve(7, 9, "朗读原文", null, Map.of(), 5);
        assertThat(result.text()).isEqualTo(original);
        assertThat(result.context()).containsEntry("source_document_id", document.getId())
                .containsEntry("source_version", document.getTextVersion())
                .containsEntry("source_start", 0).containsEntry("source_end", original.length())
                .containsEntry("source_total", original.length()).containsEntry("source_title", "小说");
        verify(repository, never()).knowledgeRecords(anyLong(), anyLong());
    }

    @Test void explicitRecordMustResolveToCanonicalDocumentInOwnedProject() {
        var chosen = document("原文", "完整正文，不是知识记录里的摘要。");
        when(index.documents(9)).thenReturn(List.of(document("其他", "其他正文"), chosen));
        when(repository.knowledgeRecord(7, 9, 31)).thenReturn(Optional.of(Map.of(
                "content", "摘要", "metadata", Map.of("document_id", chosen.getId()))));
        var result = service.resolve(7, 9, "读给我听", 31L, Map.of(), 5);
        assertThat(result.text()).isEqualTo(chosen.getText());
        assertThat(result.context()).containsEntry("source_record_id", 31L);
        verify(repository).knowledgeRecord(7, 9, 31);
    }

    @Test void unownedProjectCannotReadCanonicalTextOrRecords() {
        when(repository.ownsProject(8, 9)).thenReturn(false);
        assertThatThrownBy(() -> service.resolve(8, 9, "朗读", 31L, Map.of(), 5))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.status()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(index);
        verify(repository, never()).knowledgeRecord(anyLong(), anyLong(), anyLong());
    }

    @Test void foreignOrDeletedRecordIsRejectedRatherThanFallingBackToSoleDocument() {
        when(index.documents(9)).thenReturn(List.of(document("原文", "内容")));
        when(repository.knowledgeRecord(7, 9, 31)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve(7, 9, "朗读", 31L, Map.of(), 5))
                .hasMessageContaining("指定资料不存在");
    }

    @Test void legacyRecordWithoutCanonicalIndexRequiresReimport() {
        when(index.documents(9)).thenReturn(List.of(document("原文", "内容")));
        when(repository.knowledgeRecord(7, 9, 31)).thenReturn(Optional.of(Map.of("content", "旧摘要", "metadata", Map.of())));
        assertThatThrownBy(() -> service.resolve(7, 9, "朗读", 31L, Map.of(), 5))
                .hasMessageContaining("重新上传");
    }

    @Test void multipleSourcesNeedAnExplicitTitleAndMissingSourcesNeedAnUpload() {
        when(index.documents(9)).thenReturn(List.of(document("甲书", "内容甲"), document("乙书", "内容乙")));
        assertThatThrownBy(() -> service.resolve(7, 9, "朗读小说", null, Map.of(), 5))
                .hasMessageContaining("甲书").hasMessageContaining("乙书");
        assertThat(service.resolve(7, 9, "朗读《乙书》", null, Map.of(), 5).text()).isEqualTo("内容乙");
        assertThatThrownBy(() -> service.resolve(7, 9, "朗读甲书和乙书", null, Map.of(), 5))
                .hasMessageContaining("多份资料");
        when(index.documents(9)).thenReturn(List.of());
        assertThatThrownBy(() -> service.resolve(7, 9, "朗读小说", null, Map.of(), 5))
                .hasMessageContaining("请先上传");
    }

    @Test void chineseAndEnglishChapterRequestsSelectCompleteExactRanges() {
        String original = "前言\n第一章 甲\n正文甲。\n\n第十二章 乙\n正文乙。\nChapter 13 Gamma\nEnglish body.\nChapter 14 Delta\nEnd.";
        when(index.documents(9)).thenReturn(List.of(document("读物", original)));
        var chinese = service.resolve(7, 9, "朗读第十二章", null, Map.of(), 5);
        assertThat(chinese.text()).isEqualTo(original.substring(original.indexOf("第十二章"), original.indexOf("Chapter 13")));
        var english = service.resolve(7, 9, "Read Chapter 13 aloud", null, Map.of(), 5);
        assertThat(english.text()).isEqualTo(original.substring(original.indexOf("Chapter 13"), original.indexOf("Chapter 14")));
        assertThatThrownBy(() -> service.resolve(7, 9, "读第九章", null, Map.of(), 5))
                .hasMessageContaining("未找到指定章节");
    }

    @Test void longChapterContinuesAtExactSavedOffsetWithoutDroppingOrRepeatingCharacters() {
        String original = "第一章 开始\n" + "甲 乙\n".repeat(300) + "\n第二章 后续\n剩余。";
        var document = document("连载", original);
        when(index.documents(9)).thenReturn(List.of(document));
        var first = service.resolve(7, 9, "朗读第一章", null, Map.of(), 1);
        var second = service.resolve(7, 9, "接着读", null, first.context(), 1);
        int end = (Integer) second.context().get("source_end");
        assertThat(first.text() + second.text()).isEqualTo(original.substring(0, end));
        assertThat(second.context()).containsEntry("source_start", first.context().get("source_end"));
    }

    @Test void nextChapterStartsAtPreviousEndAndAutomaticallySelectsTheSameBook() {
        var book = document("连载", "第一章 开始\n正文。\n第二章 后续\n下一段。");
        when(index.documents(9)).thenReturn(List.of(document("其他", "内容"), book));
        var first = service.resolve(7, 9, "朗读连载第一章", null, Map.of(), 5);
        var second = service.resolve(7, 9, "下一章", null, first.context(), 5);
        assertThat(second.text()).isEqualTo("第二章 后续\n下一段。");
        assertThat(second.context()).containsEntry("source_document_id", book.getId());
    }

    @Test void nextChapterSkipsRemainingCurrentChapterWhileContinueReadsTheExactNextCharacter() {
        String original = "第一章 开始\n" + "甲".repeat(800) + "\n第二章 后续\n下一章的原文。";
        var book = document("连载", original);
        when(index.documents(9)).thenReturn(List.of(book));
        var first = service.resolve(7, 9, "朗读第一章", null, Map.of(), 1);
        var continued = service.resolve(7, 9, "接着读", null, first.context(), 1);
        assertThat(continued.context()).containsEntry("source_start", first.context().get("source_end"));
        for (String instruction : List.of("下一章", "next chapter")) {
            var next = service.resolve(7, 9, instruction, null, first.context(), 5);
            assertThat(next.context()).containsEntry("source_start", original.indexOf("第二章"));
            assertThat(next.text()).isEqualTo(original.substring(original.indexOf("第二章")));
        }
    }

    @Test void nextChapterDoesNotPretendTheRemainderOfTheLastChapterIsANewChapter() {
        var book = document("连载", "第一章 开始\n" + "甲".repeat(800));
        when(index.documents(9)).thenReturn(List.of(book));
        var first = service.resolve(7, 9, "朗读第一章", null, Map.of(), 1);
        assertThatThrownBy(() -> service.resolve(7, 9, "下一章", null, first.context(), 5))
                .hasMessageContaining("没有下一章").hasMessageContaining("继续朗读");
    }

    @Test void changedVersionAndInvalidOffsetsNeverApplyStaleProgress() {
        var book = document("连载", "完整原文");
        when(index.documents(9)).thenReturn(List.of(book));
        Map<String, Object> stale = Map.of("source_document_id", book.getId(), "source_version", "old", "source_end", 2);
        assertThatThrownBy(() -> service.resolve(7, 9, "继续读", null, stale, 5)).hasMessageContaining("原文已经更新");
        for (Object offset : List.of(-1, 999, "invalid", 1.5)) {
            Map<String, Object> invalid = Map.of("source_document_id", book.getId(), "source_version", book.getTextVersion(), "source_end", offset);
            assertThatThrownBy(() -> service.resolve(7, 9, "继续读", null, invalid, 5))
                    .isInstanceOf(ApiException.class).hasMessageContaining("朗读位置");
        }
        assertThatThrownBy(() -> service.resolve(7, 9, "继续读", null, Map.of(), 5))
                .hasMessageContaining("没有这份资料的朗读进度");
    }

    @Test void finishedSourceHasAnActionableEndInsteadOfAnEmptyAudioRequest() {
        var book = document("短文", "原文");
        when(index.documents(9)).thenReturn(List.of(book));
        var first = service.resolve(7, 9, "朗读", null, Map.of(), 5);
        assertThatThrownBy(() -> service.resolve(7, 9, "继续读", null, first.context(), 5))
                .hasMessageContaining("已经朗读完毕");
    }

    @Test void maximumBoundsTheChunkWhileRetainingANextOffsetForTheRest() {
        String original = "甲".repeat(60_000);
        when(index.documents(9)).thenReturn(List.of(document("长文", original)));
        var result = service.resolve(7, 9, "朗读", null, Map.of(), Integer.MAX_VALUE);
        assertThat(result.text()).hasSize(50_000).isEqualTo(original.substring(0, 50_000));
        assertThat(result.context()).containsEntry("source_end", 50_000).containsEntry("source_total", 60_000);
        var rest = service.resolve(7, 9, "继续读", null, result.context(), Integer.MAX_VALUE);
        assertThat(result.text() + rest.text()).isEqualTo(original);
    }

    @Test void chunkBoundaryNeverSplitsASupplementaryCharacter() {
        String original = "甲".repeat(259) + "😀" + "乙";
        when(index.documents(9)).thenReturn(List.of(document("短文", original)));
        var first = service.resolve(7, 9, "朗读", null, Map.of(), 1);
        assertThat(first.context()).containsEntry("source_end", 259);
        var rest = service.resolve(7, 9, "继续读", null, first.context(), 1);
        assertThat(first.text() + rest.text()).isEqualTo(original);
    }

    private KnowledgeIndexDocument document(String title, String text) {
        return new KnowledgeIndexDocument(UUID.randomUUID().toString(), 9, title, "upload", "text",
                Hashing.sha256(text), KnowledgeIndexStore.INDEX_VERSION, text);
    }
}
