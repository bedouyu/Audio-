package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.agent.audio.AudioAgent.CoursePlan;
import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.rag.OnnxEmbeddingService;
import com.bedouyu.audiorag.rag.QdrantService;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.repository.PlaybackProgressRepository;
import com.bedouyu.audiorag.service.ContinuousAudioService.QueueCommand;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.tools.research.WebSearchService;
import com.bedouyu.audiorag.tools.storage.MinioStorageService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:playback_progress;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "audiorag.llm.api-key="
})
class PlaybackProgressIntegrationTest {
    @Autowired AudioRagRepository records;
    @Autowired ContinuousAudioStore queues;
    @Autowired PlaybackProgressService progress;
    @Autowired PlaybackProgressRepository persisted;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    @MockitoBean AudioAgent agent;
    @MockitoBean QdrantService qdrant;
    @MockitoBean OnnxEmbeddingService embeddings;
    @MockitoBean MinioStorageService minio;
    @MockitoBean SpeechService speech;
    @MockitoBean WebSearchService web;

    @AfterEach void playbackDoesNotGenerateContentOrCallExternalServices() {
        verifyNoInteractions(agent, speech, qdrant, embeddings, minio, web);
    }

    @Test void generatedCompleteQueueIsNotAutomaticallyListenedAndGetDoesNotCreateProgress() throws Exception {
        var fixture = prepared(1);
        makeReady(fixture, 1);
        assertThat(queues.status(fixture.user(), fixture.queue())).containsEntry("status", "COMPLETE");

        var response = get(fixture.token(), fixture.queue());
        assertStatus(response, 200);
        var body = json.readTree(response.body());
        assertThat(body.path("queue_id").asText()).isEqualTo(fixture.queue());
        assertThat(body.path("segment_index").asInt()).isZero();
        assertThat(body.path("position_seconds").asDouble()).isZero();
        assertThat(body.path("playback_rate").asDouble()).isEqualTo(1.0);
        assertThat(body.path("completed").asBoolean()).isFalse();
        assertThat(progress.completed(fixture.user(), fixture.queue())).isFalse();
        assertThat(persisted.findById(fixture.queue())).isEmpty();
    }

    @Test void positionAndPlaybackRatePersistAcrossIndependentHttpRequests() throws Exception {
        var fixture = prepared(2);
        makeReady(fixture, 1);
        var response = put(fixture.token(), fixture.queue(), position(0, 37.5, 1.25, false));
        assertStatus(response, 200);
        var read = get(fixture.token(), fixture.queue());
        assertStatus(read, 200);
        var body = json.readTree(read.body());
        assertThat(body.path("segment_index").asInt()).isZero();
        assertThat(body.path("position_seconds").asDouble()).isEqualTo(37.5);
        assertThat(body.path("playback_rate").asDouble()).isEqualTo(1.25);
        assertThat(body.path("completed").asBoolean()).isFalse();
        assertThat(body.path("updated_at").asText()).isNotBlank();
        var row = persisted.findById(fixture.queue()).orElseThrow();
        assertThat(row.getUserId()).isEqualTo(fixture.user());
        assertThat(row.getPositionSeconds()).isEqualTo(37.5);
        assertThat(row.getPlaybackRate()).isEqualTo(1.25);
        assertThat(queues.status(fixture.user(), fixture.queue())).containsEntry("status", "READY");
    }

    @Test void anotherUserCannotReadOverwriteOrQueryCompletion() throws Exception {
        var fixture = prepared(1);
        makeReady(fixture, 1);
        assertStatus(put(fixture.token(), fixture.queue(), position(0, 12, 1, false)), 200);
        long stranger = user();
        String foreignToken = records.issueToken(stranger);
        assertStatus(get(foreignToken, fixture.queue()), 404);
        assertStatus(put(foreignToken, fixture.queue(), position(0, 99, 2, true)), 404);
        assertThatThrownBy(() -> progress.completed(stranger, fixture.queue())).isInstanceOf(ApiException.class);
        var original = persisted.findById(fixture.queue()).orElseThrow();
        assertThat(original.getPositionSeconds()).isEqualTo(12);
        assertThat(original.getPlaybackRate()).isEqualTo(1);
        assertThat(original.isCompleted()).isFalse();
    }

    @Test void missingAuthenticationCannotReadOrSaveProgress() throws Exception {
        var fixture = prepared(1);
        makeReady(fixture, 1);
        assertStatus(get(null, fixture.queue()), 401);
        assertStatus(put(null, fixture.queue(), position(0, 12, 1, false)), 401);
        assertThat(persisted.findById(fixture.queue())).isEmpty();
    }

    @Test void pendingAndSynthesizingSegmentsCannotReceiveListeningProgress() throws Exception {
        var fixture = prepared(2);
        assertStatus(put(fixture.token(), fixture.queue(), position(0, 10, 1, false)), 409);
        String token = queues.claimGeneration(fixture.user(), fixture.queue()).orElseThrow();
        var claimed = queues.claimNext(fixture.user(), fixture.queue(), token).orElseThrow();
        assertStatus(put(fixture.token(), fixture.queue(), position(0, 10, 1, false)), 409);
        assertStatus(put(fixture.token(), fixture.queue(), position(1, 10, 1, false)), 409);
        assertThat(persisted.findById(fixture.queue())).isEmpty();
        queues.uncertain(fixture.user(), claimed, "Test leaves the unplayed synthesis attempt for review.");
    }

    @Test void completedCanOnlyBeSavedOnLastReadySegmentOfCompleteGeneration() throws Exception {
        var fixture = prepared(2);
        makeReady(fixture, 2);
        assertStatus(put(fixture.token(), fixture.queue(), position(0, 40, 1, true)), 409);
        assertThat(persisted.findById(fixture.queue())).isEmpty();
        assertStatus(put(fixture.token(), fixture.queue(), position(1, 40, 1.5, true)), 200);
        assertThat(progress.completed(fixture.user(), fixture.queue())).isTrue();
        var response = get(fixture.token(), fixture.queue());
        assertStatus(response, 200);
        assertThat(json.readTree(response.body()).path("completed").asBoolean()).isTrue();
        assertThat(persisted.findById(fixture.queue()).orElseThrow().getSegmentIndex()).isEqualTo(1);
    }

    @Test void readyLastSegmentStillNeedsGenerationToBeReleasedBeforeCompletingListening() throws Exception {
        var fixture = prepared(1);
        String token = queues.claimGeneration(fixture.user(), fixture.queue()).orElseThrow();
        var claim = queues.claimNext(fixture.user(), fixture.queue(), token).orElseThrow();
        queues.complete(fixture.user(), claim, "/media/" + claim.audioId() + ".wav");
        assertThat(queues.status(fixture.user(), fixture.queue())).containsEntry("status", "GENERATING");
        assertStatus(put(fixture.token(), fixture.queue(), position(0, 40, 1, true)), 409);
        queues.releaseGeneration(fixture.user(), fixture.queue(), token);
        assertStatus(put(fixture.token(), fixture.queue(), position(0, 40, 1, true)), 200);
    }

    @Test void invalidPositionsAndRatesDoNotWriteRowsOrModifyGeneration() throws Exception {
        var fixture = prepared(1);
        makeReady(fixture, 1);
        for (Map<String, Object> invalid : List.of(position(-1, 0, 1, false), position(0, -1, 1, false),
                position(0, 86401, 1, false), position(0, 1, 0.49, false), position(0, 1, 3.01, false))) {
            assertStatus(put(fixture.token(), fixture.queue(), invalid), 400);
        }
        assertStatus(put(fixture.token(), fixture.queue(), position(1, 1, 1, false)), 409);
        assertThatThrownBy(() -> progress.save(fixture.user(), fixture.queue(), 0, Double.NaN, 1, false))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> progress.save(fixture.user(), fixture.queue(), 0, 1, Double.POSITIVE_INFINITY, false))
                .isInstanceOf(ApiException.class);
        assertThat(persisted.findById(fixture.queue())).isEmpty();
        assertThat(queues.status(fixture.user(), fixture.queue())).containsEntry("status", "COMPLETE");
    }

    private Fixture prepared(int count) {
        long user = user();
        long project = ((Number) records.ensureProject(user, "Playback test").get("id")).longValue();
        var command = new QueueCommand("Playback test", project, count, 1, "general", "", 1,
                UUID.randomUUID().toString(), "CONTINUOUS");
        var creation = queues.reserve(user, command);
        List<String> scripts = java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> "Test-only source text for audio segment " + (index + 1) + ".").toList();
        queues.prepared(user, creation, new CoursePlan("Playback fixture", String.join("\n", scripts), List.of(), 0), scripts);
        return new Fixture(user, project, creation.queueId(), records.issueToken(user));
    }

    private void makeReady(Fixture fixture, int count) {
        String token = queues.claimGeneration(fixture.user(), fixture.queue()).orElseThrow();
        for (int number = 0; number < count; number++) {
            var claim = queues.claimNext(fixture.user(), fixture.queue(), token).orElseThrow();
            queues.complete(fixture.user(), claim, "/media/" + claim.audioId() + ".wav");
        }
        queues.releaseGeneration(fixture.user(), fixture.queue(), token);
    }

    private long user() {
        return ((Number) records.createUser("pp-" + UUID.randomUUID(), "test-only").get("id")).longValue();
    }

    private Map<String, Object> position(int index, double seconds, double rate, boolean completed) {
        return Map.of("segment_index", index, "position_seconds", seconds, "playback_rate", rate, "completed", completed);
    }

    private HttpResponse<String> get(String token, String queue) throws Exception {
        return send(token, queue, null);
    }

    private HttpResponse<String> put(String token, String queue, Map<String, Object> body) throws Exception {
        return send(token, queue, body);
    }

    private HttpResponse<String> send(String token, String queue, Map<String, Object> body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/audio/queues/" + queue + "/progress"));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertStatus(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
    }

    private record Fixture(long user, long project, String queue, String token) { }
}
