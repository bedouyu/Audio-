package com.bedouyu.audiorag.core;

import com.bedouyu.audiorag.core.loop.AudioRetrievalLoop;
import com.bedouyu.audiorag.tools.AgentTool;
import com.bedouyu.audiorag.tools.ToolRegistry;
import com.bedouyu.audiorag.rag.QdrantService.SearchHit;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class AudioRetrievalLoopTest {
    @Test
    void networkSupplementKeepsLocalEvidenceRatherThanReplacingIt() {
        AgentTool local = mock(AgentTool.class); AgentTool web = mock(AgentTool.class);
        when(local.id()).thenReturn("knowledge_search"); when(web.id()).thenReturn("web_search");
        when(local.execute(7, "概率", 4)).thenReturn(List.of(new SearchHit("local", "本地", "概率", "file", .4, null)));
        when(web.execute(7, "概率", 3)).thenReturn(List.of(new SearchHit("web", "网络", "概率", "https://example.test", .8, null)));
        var result = new AudioRetrievalLoop(new ToolRegistry(List.of(local, web))).run(7, "概率", 4, ignored -> { });
        assertThat(result).extracting(SearchHit::id).containsExactly("local", "web");
    }
}
