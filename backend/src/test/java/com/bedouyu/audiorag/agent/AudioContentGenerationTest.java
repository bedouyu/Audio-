package com.bedouyu.audiorag.agent;

import com.bedouyu.audiorag.agent.audio.*;
import com.bedouyu.audiorag.ai.AiModelGateway;
import com.bedouyu.audiorag.core.guard.TaskSpecificationResolver;
import com.bedouyu.audiorag.core.loop.AudioRetrievalLoop;
import com.bedouyu.audiorag.memory.MemoryContextService;
import com.bedouyu.audiorag.rag.QdrantService;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.*;
import com.bedouyu.audiorag.service.content.AudioGenerationRequest;
import com.bedouyu.audiorag.tools.research.WebSearchService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AudioContentGenerationTest {
    private final AudioRagRepository repository = mock(AudioRagRepository.class);
    private final WebSearchService web = mock(WebSearchService.class);
    private final AiModelGateway model = mock(AiModelGateway.class);
    private final LearningPlanService learning = mock(LearningPlanService.class);
    private final AudioRetrievalLoop retrieval = mock(AudioRetrievalLoop.class);
    private final AudioAgent agent = new AudioAgent(mock(QdrantService.class), repository, web, model, learning,
            retrieval, mock(MemoryContextService.class), mock(AgentOrchestrator.class), new ObjectMapper(),
            mock(AgentRequestCoordinator.class), mock(AgentRunStore.class), new TaskSpecificationResolver());

    AudioContentGenerationTest() { when(repository.ownsProject(1, 2)).thenReturn(true); }

    @Test void originalEnglishAudioHasNoForcedCurriculumOrRetrieval() {
        String goal = "写一个原创侦探故事，用英语，8分钟，不要课程结构";
        when(model.audio(any(), anyList())).thenReturn(Optional.of("The detective waited beside the empty station."));
        var draft = agent.createLesson(1, new AudioAgent.LessonCommand("我的声音", 2, 5, null, "", goal, null, null));
        var request = ArgumentCaptor.forClass(AudioGenerationRequest.class);
        verify(model).audio(request.capture(), eq(List.of()));
        assertThat(request.getValue().intent().instruction()).isEqualTo(goal);
        assertThat(request.getValue().intent().language()).isEqualTo("en");
        assertThat(draft.script()).startsWith("The detective").doesNotContain("学习");
        assertThat(draft.durationMinutes()).isEqualTo(8);
        assertThat(draft.knowledgePointId()).isZero();
        verifyNoInteractions(learning, retrieval, web);
    }

    @Test void continuousContentAlsoAvoidsFixedTwelvePointAgenda() {
        when(retrieval.run(eq(2L), anyString(), eq(6), any())).thenReturn(List.of());
        when(model.audio(any(), anyList())).thenReturn(Optional.of("第一部分介绍旅行路线。\n\n第二部分介绍当地风俗。"));
        var plan = agent.createCourse(1, new AudioAgent.CourseCommand("旅行", 2, 15, null));
        assertThat(plan.knowledgePointId()).isZero();
        verifyNoInteractions(learning);
        verify(model).audio(argThat(request -> request.continuous() && request.intent().instruction().equals("旅行")), anyList());
    }

    @Test void completeSourceIsReadExactlyAndMissingSourceCannotBeInvented() {
        String original = "  第一章\r\n\r\n她说：Hello.\n末尾保留空格  ";
        var source = agent.createLesson(1, new AudioAgent.LessonCommand("小说", 2, 5, null, "",
                "逐字朗读我上传的小说第一章", original, "原书 · 第一章"));
        assertThat(source.script()).isEqualTo(original);
        assertThat(source.title()).isEqualTo("原书 · 第一章");
        assertThat(source.knowledgePointId()).isZero();
        assertThatThrownBy(() -> agent.createCourse(1, new AudioAgent.CourseCommand("朗读原文", 2, 10, null)))
                .hasMessageContaining("完整原文");
        verifyNoInteractions(model, learning, retrieval, web);
    }

    @Test void newsCannotUseLocalCacheOrModelKnowledgeWhenLiveEvidenceIsUnavailable() {
        when(web.enabled()).thenReturn(false);
        assertThatThrownBy(() -> agent.createLesson(1, new AudioAgent.LessonCommand("今天的时政", 2, 5, null, "")))
                .hasMessageContaining("实时新闻");
        when(web.enabled()).thenReturn(true);
        when(web.searchFresh(anyString(), eq(6), any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> agent.createCourse(1, new AudioAgent.CourseCommand("最新新闻", 2, 10, null)))
                .hasMessageContaining("发布日期");
        verifyNoInteractions(model, retrieval, learning);
    }

    @Test void datedNewsCitationsPreserveThePublicationAndFetchTimes() {
        var payload = new ObjectMapper().createObjectNode().put("published_at", "2026-10-20T01:00:00Z")
                .put("fetched_at", "2026-10-20T04:00:00Z").put("evidence_kind", "live-news-search-summary");
        when(web.enabled()).thenReturn(true);
        when(web.searchFresh(anyString(), eq(6), any(), any())).thenReturn(List.of(
                new QdrantService.SearchHit("web-1", "来源报道", "来源明确支持的消息", "https://example.test/news", .5, payload)));
        when(model.audio(any(), anyList())).thenReturn(Optional.of("以下是指定日期范围的报道摘要。来源报道了这条消息。[1]"));
        var draft = agent.createLesson(1, new AudioAgent.LessonCommand("2026-10-20 的新闻", 2, 5, null, ""));
        assertThat(draft.citations().get(0)).containsEntry("published_at", "2026-10-20T01:00:00Z")
                .containsEntry("fetched_at", "2026-10-20T04:00:00Z");
        verifyNoInteractions(retrieval, learning);
    }

    @Test void unavailableCreativeModelFailsRatherThanSynthesisingAPlaceholderLesson() {
        when(model.audio(any(), anyList())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> agent.createLesson(1, new AudioAgent.LessonCommand("写一个原创童话故事", 2, 5, null, "")))
                .hasMessageContaining("没有用课程模板");
        verifyNoInteractions(learning, retrieval);
    }

    @Test void authenticatedPriorContentIsDataWhileOnlyTheNewInstructionControlsIntent() {
        String previous = "今天的新闻，60分钟，用中文。旧正文尚未验证，不是本次新闻证据。";
        String current = "缩短刚才内容，换成英文，3分钟";
        when(model.audio(any(), anyList())).thenReturn(Optional.of("Here is a shorter version of the previous audio."));
        var draft = agent.createLesson(1, new AudioAgent.LessonCommand("音频", 2, 5, null, "", current, null, null, previous));
        var request = ArgumentCaptor.forClass(AudioGenerationRequest.class);
        verify(model).audio(request.capture(), eq(List.of()));
        assertThat(request.getValue().intent().instruction()).isEqualTo(current);
        assertThat(request.getValue().intent().language()).isEqualTo("en");
        assertThat(request.getValue().intent().durationMinutes()).isEqualTo(3);
        assertThat(request.getValue().intent().freshEvidenceRequired()).isFalse();
        assertThat(request.getValue().contextScript()).isEqualTo(previous);
        assertThat(draft.knowledgePointId()).isZero();
        verifyNoInteractions(retrieval, web, learning);
    }

    @Test void sourceTextStillWinsExactlyWhenPriorScriptIsAlsoAvailable() {
        String source = "  第一章原文\r\n不可修改。  ";
        var plan = agent.createCourse(1, new AudioAgent.CourseCommand("原书", 2, 5, null,
                "接着读下一章", source, "原书第一章", "这是旧生成稿，不应覆盖原文。"));
        assertThat(plan.script()).isEqualTo(source);
        verifyNoInteractions(model, retrieval, web, learning);
    }
}
