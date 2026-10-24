package com.bedouyu.audiorag.controller;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.agent.audio.AudioAgent.ChatCommand;
import com.bedouyu.audiorag.model.dto.ChatRequest;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.service.*;
import com.bedouyu.audiorag.tools.audio.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatAudioQueueContextTest {
    @Test void selectedAudioQueueIsPassedFromTheWireRequestToTheAgentCommand() {
        String queue = "104ec5da-2bae-4500-83af-7e925cfc6648";
        var request = new ObjectMapper().readValue("{\"message\":\"改得口语一点\",\"project_id\":7,"
                + "\"audio_queue_id\":\"" + queue + "\"}", ChatRequest.class);
        var auth = mock(AuthService.class);
        when(auth.requireUser("Bearer test")).thenReturn(Map.of("id", 1L));
        var agent = mock(AudioAgent.class);
        when(agent.chat(eq(1L), any(ChatCommand.class))).thenReturn(Map.of("status", "SUCCEEDED"));
        var controller = new AiController(auth, agent, mock(SpeechService.class), mock(AudioRagRepository.class),
                mock(ContinuousAudioService.class), mock(KnowledgeService.class), mock(TranscriptionService.class),
                mock(LearningPlanService.class));
        controller.chat("Bearer test", request);
        var command = ArgumentCaptor.forClass(ChatCommand.class);
        verify(agent).chat(eq(1L), command.capture());
        assertThat(command.getValue().audioQueueId()).isEqualTo(queue);
        assertThat(command.getValue().projectId()).isEqualTo(7);
    }
}
