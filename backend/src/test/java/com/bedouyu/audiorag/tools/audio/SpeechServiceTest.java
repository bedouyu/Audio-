package com.bedouyu.audiorag.tools.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bedouyu.audiorag.common.ApiException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class SpeechServiceTest {
    @TempDir
    Path directory;

    @Test
    void selectsInstalledEnglishVoiceEvenWhenChineseIsFirst() {
        assertEquals("English", SpeechService.selectVoice(inventory(), "", "en"));
        assertEquals("English", SpeechService.selectVoice(inventory(), null, "en-US"));
        assertEquals("Chinese", SpeechService.selectVoice(inventory(), "", "zh"));
    }

    @Test
    void requiresRequestedLocaleInsteadOfAnotherLocaleOfSameLanguage() {
        ApiException exception = assertThrows(ApiException.class,
                () -> SpeechService.selectVoice(inventory(), "", "en-GB"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status());
    }

    @Test
    void rejectsUnknownDisabledAndIncompatibleVoices() {
        for (String unavailable : List.of("Unknown", "Disabled")) {
            ApiException exception = assertThrows(ApiException.class,
                    () -> SpeechService.selectVoice(inventory(), unavailable, "en"));
            assertEquals(HttpStatus.BAD_REQUEST, exception.status());
        }
        assertThrows(ApiException.class, () -> SpeechService.selectVoice(inventory(), "Chinese", "en"));
    }

    @Test
    void missingEnglishVoiceNeverFallsBackToChinese() {
        ApiException exception = assertThrows(ApiException.class,
                () -> SpeechService.selectVoice(List.of(voice("Chinese", "zh-CN", true)), "", "en"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status());
        assertTrue(exception.getMessage().contains("en"));
    }

    @Test
    void bilingualSelectionRequiresBothLanguagesAndPreservesOptionalPrimaryVoice() {
        assertEquals("", SpeechService.selectVoice(inventory(), "", "bilingual"));
        assertEquals("English", SpeechService.selectVoice(inventory(), "English", "bilingual"));
        assertThrows(ApiException.class, () -> SpeechService.selectVoice(
                List.of(voice("English", "en-US", true)), "English", "bilingual"));
        assertThrows(ApiException.class, () -> SpeechService.selectVoice(
                List.of(voice("Chinese", "zh-CN", true)), "", "bilingual"));
    }

    @Test
    void supportsExplicitInstalledLanguagesBeyondEnglishAndChinese() {
        List<Map<String, Object>> french = List.of(voice("French", "fr-FR", true));
        assertEquals("French", SpeechService.selectVoice(french, "", "fr"));
        assertThrows(ApiException.class, () -> SpeechService.selectVoice(french, "French", "bilingual"));
    }

    @Test
    void detectsEnglishChineseAndMixedTextBeforeSynthesis() {
        assertEquals("en", SpeechService.detectLanguage("Today's story: Learning to listen."));
        assertEquals("zh", SpeechService.detectLanguage("今天的故事：学会倾听。"));
        assertEquals("bilingual", SpeechService.detectLanguage("Hello, world! 你好，世界。"));
        assertEquals("zh", SpeechService.detectLanguage("12345。"));
        assertThrows(ApiException.class, () -> SpeechService.detectLanguage("Привет"));
    }

    @Test
    void discoveryFailureIsReportedWithoutInventingVoices() throws IOException {
        SpeechService speech = new SpeechService(directory.toString(), directory.resolve("missing.ps1").toString());
        ApiException exception = assertThrows(ApiException.class, speech::voices);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status());
        assertTrue(exception.getMessage().contains("脚本不存在"));
    }

    @Test
    void invalidVoiceFailsBeforeWritingTextOrStartingSynthesis() throws IOException {
        SpeechService speech = stubSpeech(List.of(voice("Chinese", "zh-CN", true)));
        String id = "b6bcd776-7483-4425-9598-cf4af944b60a";
        assertThrows(ApiException.class, () -> speech.synthesize(id, "An English story", "Chinese"));
        assertFalse(Files.exists(directory.resolve(id + ".txt")));
        assertFalse(Files.exists(directory.resolve(id + ".wav")));
    }

    private SpeechService stubSpeech(List<Map<String, Object>> voices) throws IOException {
        return new SpeechService(directory.toString(), directory.resolve("not-invoked.ps1").toString()) {
            @Override
            public List<Map<String, Object>> voices() { return voices; }
        };
    }

    private static List<Map<String, Object>> inventory() {
        return List.of(voice("Chinese", "zh-CN", true), voice("English", "en-US", true),
                voice("Disabled", "en-GB", false));
    }

    private static Map<String, Object> voice(String name, String locale, boolean enabled) {
        return Map.of("name", name, "locale", locale, "culture", locale, "enabled", enabled);
    }
}
