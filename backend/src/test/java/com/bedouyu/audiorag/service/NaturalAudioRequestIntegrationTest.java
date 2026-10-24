package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.agent.audio.AudioAgent.CoursePlan;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.rag.OnnxEmbeddingService;
import com.bedouyu.audiorag.rag.QdrantService;
import com.bedouyu.audiorag.rag.index.KnowledgeIndexStore;
import com.bedouyu.audiorag.rag.strategy.TextSegment;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.repository.ContinuousAudioQueueRepository;
import com.bedouyu.audiorag.repository.LearningPointRepository;
import com.bedouyu.audiorag.service.ContinuousAudioService.QueueCommand;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.tools.research.WebSearchService;
import com.bedouyu.audiorag.tools.storage.MinioStorageService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:natural_audio_requests;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "audiorag.llm.api-key="
})
class NaturalAudioRequestIntegrationTest {
    @Autowired AudioRagRepository records;
    @Autowired ContinuousAudioStore queues;
    @Autowired PlaybackProgressService playback;
    @Autowired KnowledgeIndexStore index;
    @Autowired LearningPointRepository learningPoints;
    @Autowired ContinuousAudioQueueRepository queueRows;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    @MockitoBean ContinuousAudioService audio;
    @MockitoBean AudioAgent agent;
    @MockitoBean QdrantService qdrant;
    @MockitoBean OnnxEmbeddingService embeddings;
    @MockitoBean MinioStorageService minio;
    @MockitoBean SpeechService speech;
    @MockitoBean WebSearchService web;

    @BeforeEach void returnReservedQueueWithoutWorkers() {
        when(audio.startInstruction(anyLong(), any(), any())).thenAnswer(call -> {
            long user = call.getArgument(0);
            ContinuousAudioStore.Creation creation = call.getArgument(2);
            return queues.status(user, creation.queueId());
        });
        when(audio.proposeInstruction(anyLong(), anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            long user = call.getArgument(0);
            String queue = call.getArgument(1), kind = call.getArgument(3), key = call.getArgument(4);
            var result = new LinkedHashMap<>(queues.status(user, queue));
            result.put("script_revision", Map.of("id", key, "queue_id", queue, "kind", kind, "status", "PENDING"));
            return result;
        });
    }

    @AfterEach void reservingReplayingAndResumingNeverInvokesExternalServices() {
        verifyNoInteractions(agent, speech, qdrant, embeddings, minio, web);
    }

    @Test void aNaturalInstructionCreatesItsSpaceAndQueueWithoutAnyLearningPlanOrModule() throws Exception {
        long user = user();
        String instruction = "写一个温柔的原创睡前故事，用英语，五分钟。";
        String request = uuid();
        var response = post(user, payload(instruction, request));
        assertStatus(response, 202);
        var body = json.readTree(response.body());
        assertThat(body.path("action").asText()).isEqualTo("GENERATE");
        assertThat(body.path("request_id").asText()).isEqualTo(request);
        long project = body.path("project").path("id").asLong();
        assertThat(records.listProjects(user)).hasSize(1);
        assertThat(learningPoints.countByProjectId(project)).isZero();
        var command = queues.commandForUser(user, body.path("queue_id").asText());
        assertThat(command.instruction()).isEqualTo(instruction);
        assertThat(command.context()).containsEntry("entrypoint", "natural")
                .containsEntry("language", "en").containsEntry("request_instruction", instruction);
        assertThat(command.sourceText()).isNull();
    }

    @Test void requestKeysAreIdempotentAcrossSpacesAndScopedToTheUser() throws Exception {
        long user = user();
        String request = uuid();
        var original = post(user, payload("解释海浪为什么会发出声音", request));
        assertStatus(original, 202);
        String firstQueue = queue(original);
        assertStatus(post(user, payload("介绍森林里的鸟鸣", uuid())), 202);
        var replay = post(user, payload("解释海浪为什么会发出声音", request.toUpperCase(java.util.Locale.ROOT)));
        assertStatus(replay, 202);
        assertThat(queue(replay)).isEqualTo(firstQueue);
        assertThat(json.readTree(replay.body()).path("action").asText()).isEqualTo("RESUME");
        assertThat(queues.history(user, null)).hasSize(2);
        assertThat(records.listProjects(user)).hasSize(2);
        verify(audio).startInstruction(eq(user), any(), argThat(creation ->
                !creation.created() && firstQueue.equals(creation.queueId())));

        long anotherUser = user();
        var independent = post(anotherUser, payload("解释海浪为什么会发出声音", request));
        assertStatus(independent, 202);
        assertThat(queue(independent)).isNotEqualTo(firstQueue);
        assertThat(queues.history(anotherUser, null)).hasSize(1);
    }

    @Test void aPersistedKeyRejectsChangedInstructionProjectOrVoiceBeforeDispatch() throws Exception {
        long user = user();
        String request = uuid();
        String instruction = "解释雨声如何形成";
        assertStatus(post(user, payload(instruction, request)), 202);
        long alternate = project(user, "另一处音频空间");
        var differentProject = payload(instruction, request);
        differentProject.put("project_id", alternate);
        var differentVoice = payload(instruction, request);
        differentVoice.put("voice", "another-voice");
        for (var changed : List.of(payload("解释风声如何形成", request), differentProject, differentVoice)) {
            assertStatus(post(user, changed), 409);
        }
        assertThat(queues.history(user, null)).hasSize(1);
        verify(audio, times(1)).startInstruction(eq(user), any(), any());
    }

    @Test void allFiveThousandInstructionCharactersReachTheDurableCommand() throws Exception {
        long user = user();
        String prefix = "介绍自然界的声音，并保留以下全部要求：";
        String suffix = "最后要讲海豚。";
        String instruction = prefix + "甲".repeat(5000 - prefix.length() - suffix.length()) + suffix;
        var response = post(user, payload(instruction, uuid()));
        assertStatus(response, 202);
        var command = queues.commandForUser(user, queue(response));
        assertThat(command.instruction()).hasSize(5000).isEqualTo(instruction);
        assertThat(command.context()).containsEntry("request_instruction", instruction);
        assertThat(command.topic()).hasSizeLessThanOrEqualTo(500);
        assertThat(records.listProjects(user)).hasSize(1);
        assertStatus(post(user, payload(instruction + "甲", uuid())), 422);
        assertThat(queues.history(user, null)).hasSize(1);
    }

    @Test void aForeignProjectCannotBeUsedToCreateANaturalQueue() throws Exception {
        long owner = user(), stranger = user();
        long foreign = project(owner, "只有所有者可以收听");
        var request = payload("介绍海洋声音", uuid());
        request.put("project_id", foreign);
        assertStatus(post(stranger, request), 404);
        assertThat(queues.history(stranger, null)).isEmpty();
        assertThat(records.listProjects(stranger)).isEmpty();
        verifyNoInteractions(audio);
    }

    @Test void invalidRequestKeysAndEmptyInstructionsFailBeforeCreatingAnySpace() throws Exception {
        long user = user();
        assertStatus(post(user, payload("介绍自然声音", "not-a-uuid")), 400);
        assertStatus(post(user, payload("   ", uuid())), 422);
        assertStatus(post(user, Map.of("instruction", "介绍自然声音")), 422);
        assertThat(records.listProjects(user)).isEmpty();
        assertThat(queues.history(user, null)).isEmpty();
        verifyNoInteractions(audio);
    }

    @Test void explicitSourceReadingUsesCanonicalTextAndPreservesEveryCharacter() throws Exception {
        long user = user();
        var source = source(user);
        var request = payload("逐字朗读这本小说第一章", uuid());
        request.put("project_id", source.project());
        request.put("source_record_id", source.record());
        var response = post(user, request);
        assertStatus(response, 202);
        var command = queues.commandForUser(user, queue(response));
        assertThat(command.sourceText()).isEqualTo(source.firstChapter());
        assertThat(command.sourceTitle()).isEqualTo("测试小说");
        assertThat(command.context()).containsEntry("exact_source", true)
                .containsEntry("source_document_id", source.document())
                .containsEntry("source_start", 0).containsEntry("source_end", source.firstChapter().length());
        assertThat(command.sourceText()).doesNotContain("摘要并非原文");
        assertThat(learningPoints.countByProjectId(source.project())).isZero();
    }

    @Test void aSourceRecordAloneInfersItsOwnedSpaceAndSummaryKeepsCanonicalTextAsData() throws Exception {
        long user = user();
        var source = source(user);
        var reading = payload("朗读第一章", uuid());
        reading.put("source_record_id", source.record());
        var read = post(user, reading);
        assertStatus(read, 202);
        var readingCommand = queues.commandForUser(user, queue(read));
        assertThat(readingCommand.projectId()).isEqualTo(source.project());
        assertThat(readingCommand.sourceText()).isEqualTo(source.firstChapter());
        assertThat(readingCommand.context()).containsEntry("exact_source", true);

        String instruction = "概括这份资料，制作一分钟播客";
        var summary = payload(instruction, uuid());
        summary.put("source_record_id", source.record());
        var response = post(user, summary);
        assertStatus(response, 202);
        var command = queues.commandForUser(user, queue(response));
        assertThat(command.projectId()).isEqualTo(source.project());
        assertThat(command.instruction()).isEqualTo(instruction);
        assertThat(command.sourceText()).isEqualTo(source.firstChapter());
        assertThat(command.context()).containsEntry("exact_source", false)
                .containsEntry("source_document_id", source.document())
                .containsEntry("source_start", 0).containsEntry("source_total", source.text().length());
        assertThat(records.listProjects(user)).hasSize(1);
    }

    @Test void sourceRecordInferenceCannotReadAnotherUsersKnowledge() throws Exception {
        long owner = user(), stranger = user();
        var source = source(owner);
        var request = payload("朗读第一章", uuid());
        request.put("source_record_id", source.record());
        assertStatus(post(stranger, request), 404);
        assertThat(records.listProjects(stranger)).isEmpty();
        assertThat(queues.history(stranger, null)).isEmpty();
        verifyNoInteractions(audio);
    }

    @Test void latestResumeReturnsExistingGeneratedAudioWhenItHasNotActuallyBeenListenedTo() throws Exception {
        long user = user();
        var original = post(user, payload("讲解海豚如何交流", uuid()));
        assertStatus(original, 202);
        String originalQueue = queue(original);
        makeReady(user, originalQueue);
        assertThat(queues.status(user, originalQueue)).containsEntry("status", "COMPLETE");
        assertThat(playback.completed(user, originalQueue)).isFalse();

        var resumed = post(user, payload("继续听", uuid()));
        assertStatus(resumed, 200);
        assertThat(queue(resumed)).isEqualTo(originalQueue);
        assertThat(json.readTree(resumed.body()).path("action").asText()).isEqualTo("RESUME");
        assertThat(queues.history(user, null)).hasSize(1);
        verify(audio).startInstruction(eq(user), any(), argThat(creation ->
                !creation.created() && originalQueue.equals(creation.queueId())));
    }

    @Test void naturalReadingContinuationResumesUnfinishedPlaybackInsteadOfSkippingUnreadSource() throws Exception {
        long user = user(); var source = source(user);
        var first = payload("朗读第一章原文", uuid()); first.put("source_record_id", source.record());
        var original = post(user, first); assertStatus(original, 202); String originalQueue = queue(original);
        makeReady(user, originalQueue);
        for (String instruction : List.of("继续读这本小说", "接着读这本书", "继续朗读《测试小说》", "接着听")) {
            var body = payload(instruction, uuid()); body.put("previous_queue_id", originalQueue);
            var resumed = post(user, body); assertStatus(resumed, 200);
            assertThat(queue(resumed)).isEqualTo(originalQueue);
            assertThat(json.readTree(resumed.body()).path("action").asText()).isEqualTo("RESUME");
        }
        assertThat(queues.history(user, null)).hasSize(1);
    }

    @Test void aResumeReceiptRemainsIdempotentAfterListeningCompletesAndTheLatestQueueChanges() throws Exception {
        long user = user();
        var original = post(user, payload("介绍海豚之间的声音交流", uuid()));
        assertStatus(original, 202);
        String originalQueue = queue(original);
        makeReady(user, originalQueue);
        String resumeKey = uuid();
        var resume = post(user, payload("继续听", resumeKey));
        assertStatus(resume, 200);
        assertThat(queue(resume)).isEqualTo(originalQueue);
        playback.save(user, originalQueue, 0, 30, 1, true);

        var newer = post(user, payload("介绍海底的声音", uuid()));
        assertStatus(newer, 202);
        assertThat(queue(newer)).isNotEqualTo(originalQueue);
        var replay = post(user, payload("继续听", resumeKey));
        assertStatus(replay, 200);
        assertThat(queue(replay)).isEqualTo(originalQueue);
        assertThat(queues.history(user, null)).hasSize(2);
        assertStatus(post(user, payload("改成英语", resumeKey)), 409);
        verify(audio, times(2)).startInstruction(eq(user), any(), argThat(creation ->
                !creation.created() && originalQueue.equals(creation.queueId())));
    }

    @Test void completingSourcePlaybackMakesContinueStartAtTheNextExactOffset() throws Exception {
        long user = user();
        var source = source(user);
        var originalRequest = payload("朗读第一章原文", uuid());
        originalRequest.put("project_id", source.project());
        originalRequest.put("source_record_id", source.record());
        var original = post(user, originalRequest);
        assertStatus(original, 202);
        String previousQueue = queue(original);
        makeReady(user, previousQueue);
        playback.save(user, previousQueue, 0, 30, 1, true);
        assertThat(playback.completed(user, previousQueue)).isTrue();

        var next = post(user, payload("继续读", uuid()));
        assertStatus(next, 202);
        assertThat(queue(next)).isNotEqualTo(previousQueue);
        assertThat(json.readTree(next.body()).path("action").asText()).isEqualTo("GENERATE");
        var command = queues.commandForUser(user, queue(next));
        assertThat(command.projectId()).isEqualTo(source.project());
        assertThat(command.sourceText()).isEqualTo(source.rest());
        assertThat(command.context()).containsEntry("previous_queue_id", previousQueue)
                .containsEntry("source_document_id", source.document())
                .containsEntry("source_start", source.firstChapter().length())
                .containsEntry("source_end", source.text().length());
        assertThat(queues.history(user, null)).hasSize(2);
    }

    @Test void nextChapterInheritsTheCanonicalSourceEvenWithoutAReadingModeSelection() throws Exception {
        long user = user();
        var source = source(user);
        var originalRequest = payload("朗读第一章原文", uuid());
        originalRequest.put("project_id", source.project());
        originalRequest.put("source_record_id", source.record());
        var original = post(user, originalRequest);
        assertStatus(original, 202);
        var next = post(user, payload("下一章", uuid()));
        assertStatus(next, 202);
        var command = queues.commandForUser(user, queue(next));
        assertThat(command.sourceText()).isEqualTo(source.rest());
        assertThat(command.context()).containsEntry("exact_source", true)
                .containsEntry("source_start", source.firstChapter().length());
    }

    @Test void anExplicitPreviousQueueStagesReviewOnTheChosenAudioInsteadOfCreatingANewQueue() throws Exception {
        long user = user();
        var chosen = post(user, payload("写一个原创英文故事，十分钟", uuid()));
        assertStatus(chosen, 202);
        String chosenQueue = queue(chosen);
        var original = queues.commandForUser(user, chosenQueue);
        makeReady(user, chosenQueue);
        var latest = post(user, payload("介绍雨声，三分钟，用中文", uuid()));
        assertStatus(latest, 202);
        String latestQueue = queue(latest);
        assertThat(latestQueue).isNotEqualTo(chosenQueue);

        String key = uuid();
        var edit = payload("短一点", key);
        edit.put("previous_queue_id", chosenQueue);
        var response = post(user, edit);
        assertStatus(response, 200);
        assertThat(queue(response)).isEqualTo(chosenQueue);
        assertThat(json.readTree(response.body()).path("action").asText()).isEqualTo("REVIEW");
        assertThat(json.readTree(response.body()).path("script_revision").path("status").asText()).isEqualTo("PENDING");
        var command = queues.commandForUser(user, queue(response));
        assertThat(command.projectId()).isEqualTo(original.projectId());
        assertThat(command).isEqualTo(original);
        assertThat(queues.history(user, null)).hasSize(2);
        verify(audio).proposeInstruction(user, chosenQueue, "短一点", "REWRITE", key);
        var replay = post(user, edit);
        assertStatus(replay, 200);
        assertThat(queue(replay)).isEqualTo(chosenQueue);
        assertThat(json.readTree(replay.body()).path("action").asText()).isEqualTo("REVIEW");
        var changedReference = new LinkedHashMap<>(edit);
        changedReference.put("previous_queue_id", latestQueue);
        assertStatus(post(user, changedReference), 409);

        long stranger = user();
        var foreignEdit = payload("短一点", uuid());
        foreignEdit.put("previous_queue_id", chosenQueue);
        assertStatus(post(stranger, foreignEdit), 404);
        assertThat(queues.history(stranger, null)).isEmpty();
    }

    @Test void naturalAppendAlsoStagesReviewAndDoesNotDispatchAnotherAudioRequest() throws Exception {
        long user = user();
        var original = post(user, payload("讲一个中文故事", uuid()));
        String queue = queue(original);
        makeReady(user, queue);
        String key = uuid();
        var request = payload("按这个格式再来几句", key);
        request.put("previous_queue_id", queue);
        var response = post(user, request);
        assertStatus(response, 200);
        assertThat(queue(response)).isEqualTo(queue);
        assertThat(json.readTree(response.body()).path("action").asText()).isEqualTo("REVIEW");
        verify(audio).proposeInstruction(user, queue, "按这个格式再来几句", "EXTEND", key);
        verify(audio, times(1)).startInstruction(eq(user), any(), any());
        assertThat(queues.history(user, null)).hasSize(1);
    }

    @Test void aNewTopicMentioningModificationDoesNotBecomeAnEditOfTheLoadedAudio() throws Exception {
        long user = user();
        String previous = queue(post(user, payload("介绍中文故事", uuid())));
        var request = payload("帮我做一期关于修改个人信息的安全常识", uuid());
        request.put("previous_queue_id", previous);
        var response = post(user, request);
        assertStatus(response, 202);
        assertThat(json.readTree(response.body()).path("action").asText()).isEqualTo("GENERATE");
        assertThat(queue(response)).isNotEqualTo(previous);
        verify(audio, never()).proposeInstruction(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void followUpWithoutAnyPreviousContextReturnsAnActionableValidationError() throws Exception {
        long user = user();
        for (String instruction : List.of("继续", "改成英语", "下一章")) {
            var response = post(user, payload(instruction, uuid()));
            assertStatus(response, 422);
            assertThat(json.readTree(response.body()).path("detail").asText()).isNotBlank();
        }
        assertThat(records.listProjects(user)).isEmpty();
        assertThat(queues.history(user, null)).isEmpty();
        verifyNoInteractions(audio);
    }

    private Source source(long user) {
        long project = project(user, "原文收听");
        String first = "  第一章 开始\r\n  一行原文。\r\n\r\n";
        String rest = "第二章 后续\r\n  后文原样。  \r\n";
        String text = first + rest;
        String document = uuid();
        index.save(project, document, "测试小说", "upload", "text", Hashing.sha256(text), text,
                List.of(new TextSegment(document, 0, 0, text.length(), text, text.length())), List.of(uuid()));
        long record = records.addProjectRecord(project, "knowledge", "测试小说", "摘要并非原文",
                Map.of("document_id", document));
        return new Source(project, record, document, first, rest, text);
    }

    private void makeReady(long user, String queue) {
        QueueCommand command = queues.commandForUser(user, queue);
        String script = command.sourceText() == null ? "A test-only generated script." : command.sourceText();
        String token = queueRows.findById(queue).orElseThrow().getLeaseToken();
        queues.prepared(user, new ContinuousAudioStore.Creation(queue, token, true),
                new CoursePlan("Prepared content", script, List.of(), 0), List.of(script));
        String generation = queues.claimGeneration(user, queue).orElseThrow();
        var claim = queues.claimNext(user, queue, generation).orElseThrow();
        queues.complete(user, claim, "/media/" + claim.audioId() + ".wav");
        queues.releaseGeneration(user, queue, generation);
    }

    private long user() {
        return ((Number) records.createUser("nr-" + uuid(), "test-only").get("id")).longValue();
    }
    private long project(long user, String title) {
        return ((Number) records.ensureProject(user, title).get("id")).longValue();
    }
    private String uuid() { return UUID.randomUUID().toString(); }
    private LinkedHashMap<String, Object> payload(String instruction, String request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("instruction", instruction); body.put("request_id", request);
        return body;
    }
    private String queue(HttpResponse<String> response) { return json.readTree(response.body()).path("queue_id").asText(); }
    private HttpResponse<String> post(long user, Map<String, Object> body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/audio/requests"))
                .header("Authorization", "Bearer " + records.issueToken(user)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void assertStatus(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
    }
    private record Source(long project, long record, String document, String firstChapter, String rest, String text) { }
}
