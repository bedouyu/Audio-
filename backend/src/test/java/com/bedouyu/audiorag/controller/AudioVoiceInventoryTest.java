package com.bedouyu.audiorag.controller;

import com.bedouyu.audiorag.service.*;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AudioVoiceInventoryTest {
    @Test void voiceApiPreservesProviderDisplayNamesAndMultilingualCapabilities() {
        AuthService auth = mock(AuthService.class);
        SpeechService speech = mock(SpeechService.class);
        when(auth.requireUser("Bearer local-test")).thenReturn(Map.of("id", 7));
        when(speech.voices()).thenReturn(List.of(Map.of("name", "cosyvoice-official-demo", "display_name", "官方示例音色",
                "locale", "bilingual", "languages", List.of("zh", "en"), "enabled", true)));
        var controller = new AudioInstructionController(auth, mock(NaturalAudioRequestStore.class),
                mock(ContinuousAudioService.class), mock(ContinuousAudioStore.class), speech);
        var response = controller.voices("Bearer local-test");
        @SuppressWarnings("unchecked") var voices = (List<Map<String, Object>>) response.get("voices");
        assertThat(voices).hasSize(1);
        assertThat(voices.get(0)).containsEntry("display_name", "官方示例音色")
                .containsEntry("language", "bilingual").containsEntry("languages", List.of("zh", "en"));
        verify(auth).requireUser("Bearer local-test");
    }
}
