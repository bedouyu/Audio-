package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.agent.audio.AudioAgent;
import com.bedouyu.audiorag.agent.audio.AudioAgent.CoursePlan;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.rag.*;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.tools.research.WebSearchService;
import com.bedouyu.audiorag.tools.storage.MinioStorageService;
import java.net.URI;
import java.net.http.*;
import java.nio.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** HTTP -> real services -> real H2 transactions; only model, speech and external stores are replaced. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "spring.datasource.url=jdbc:h2:mem:script_revision_http;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "audiorag.llm.api-key="
})
class ScriptRevisionHttpIntegrationTest {
    private static final String ORIGINAL = "这是当前正在播放的中文讲稿，讲述月亮的来历和美丽的夜空。";
    private static final String EDITED = "这是经过用户修改的中文讲稿，讲述月亮的形成和美丽的夜空。";
    private static final String CHINESE_REWRITE = "在当前讲稿的基础上修改全篇为中文的中国散文";
    private static final String CHINESE_PROSE = """
            老院的春天，总是从一场细雨开始。雨水落进门前的石缝，唤醒了沉睡一冬的苔藓，也把远行人的心悄悄洗亮。

            我推开那扇旧木门，熟悉的吱呀声便从掌心传来。院子中央的老树已经发芽，嫩绿的叶片迎着晨光，像一封封刚刚拆开的家书。

            母亲在檐下择菜，竹篮里盛着带露水的青叶。她抬起头来，笑着说饭快好了，那平常的一句话，竟让我走过的千山万水都有了归处。

            灶间升起淡淡的炊烟，米饭的香气沿着墙角慢慢散开。墙上的旧钟还在走着，把急促的日子一点一点，送回可以从容呼吸的清晨。

            午后，邻家的孩子跑过巷口，笑声落在青石板上。风吹动晾晒的衣裳，也吹过年少时的梦，让那些远去的名字重新有了温柔的回声。

            我在树下坐了很久，看一只鸟衔着细草飞向屋檐。它不问春天从哪里来，只忙着把一个小小的家，安放在足以遮风避雨的地方。

            原来岁月留下的，并不只有门框上的刻痕。还有每次归来时有人应门，有一盏灯始终亮着，有一碗热汤在安静地等候。

            傍晚的风带来泥土的清香，夕阳把院墙染成柔和的金色。我听见远处传来熟悉的脚步声，知道平凡的一天，也可以值得认真珍惜。

            夜色渐深，窗外的树影轻轻摇晃。我把一路的奔忙暂时放下，让心像一枚落叶，稳稳停在故乡宽厚的掌心。

            明天我或许又要启程，但老院的春天会留在心里。它提醒我，无论走到多远，只要记得爱与归来，生命就总有可以重新出发的清晨。
            """.strip();
    @Autowired AudioRagRepository records;
    @Autowired ContinuousAudioStore queues;
    @Autowired ObjectMapper json;
    @Value("${local.server.port}") int port;
    @MockitoBean AudioAgent agent;
    @MockitoBean ScriptReviser reviser;
    @MockitoBean QdrantService qdrant;
    @MockitoBean OnnxEmbeddingService embeddings;
    @MockitoBean MinioStorageService minio;
    @MockitoBean SpeechService speech;
    @MockitoBean WebSearchService web;
    @MockitoBean StringRedisTemplate redis;
    @TempDir Path directory;
    long user, project;
    String queue, token;

    @BeforeEach void prepareLiveAudioWithoutCallingAProvider() throws Exception {
        user = ((Number) records.createUser("review-http-" + UUID.randomUUID().toString().substring(0, 16), "test-only").get("id")).longValue();
        project = ((Number) records.ensureProject(user, "月亮讲稿").get("id")).longValue();
        token = records.issueToken(user);
        var command = new ContinuousAudioService.QueueCommand("月亮", project, 3, 1, null, null, 1,
                UUID.randomUUID().toString(), "CONTINUOUS");
        var creation = queues.reserve(user, command);
        queue = creation.queueId();
        queues.prepared(user, creation, new CoursePlan("月亮", ORIGINAL, List.of(), 0), List.of(ORIGINAL));
        String generation = queues.claimGeneration(user, queue).orElseThrow();
        var claim = queues.claimNext(user, queue, generation).orElseThrow();
        queues.complete(user, claim, "/media/" + claim.audioId() + ".wav");
        queues.releaseGeneration(user, queue, generation);
        Path wave = directory.resolve("fixture.wav");
        Files.write(wave, ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0x46464952).putInt(38).putInt(0x45564157).putInt(0x20746d66).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(24000).putInt(48000)
                .putShort((short) 2).putShort((short) 16).putInt(0x61746164).putInt(2).putShort((short) 1).array());
        when(speech.audioFile(anyString())).thenReturn(wave);
        when(speech.synthesize(anyString(), anyString(), any())).thenAnswer(call -> "/media/" + call.getArgument(0) + ".wav");
    }

    @AfterEach void noRealExternalServicesWereUsed() {
        verifyNoInteractions(agent, qdrant, embeddings, minio, web);
    }

    @Test void manualChangeIsReviewedBeforeAcceptanceAndAnAcceptedChangeCanBeReverted() throws Exception {
        var original = getQueue();
        String oldAudio = original.path("segments").get(0).path("audio_id").asText();
        var staged = request("POST", base() + "/segments/1/revise", Map.of("text", EDITED,
                "expected_script_hash", Hashing.sha256(ORIGINAL)), token, 200);
        String revision = staged.path("script_revision").path("id").asText();
        assertThat(staged.path("script_revision").path("before_text").asText()).isEqualTo(ORIGINAL);
        assertThat(staged.path("script_revision").path("after_text").asText()).isEqualTo(EDITED);
        assertThat(getQueue().path("segments").get(0).path("script").asText()).isEqualTo(ORIGINAL);
        assertThat(getQueue().path("segments").get(0).path("audio_id").asText()).isEqualTo(oldAudio);
        verify(speech, never()).synthesize(anyString(), anyString(), any());
        request("POST", revision(revision) + "/accept", Map.of("confirmed", false), token, 400);
        request("POST", revision(revision) + "/accept", Map.of("confirmed", true), token, 200);
        awaitComplete();
        assertThat(getQueue().path("segments").get(0).path("script").asText()).isEqualTo(EDITED);
        request("POST", revision(revision) + "/accept", Map.of("confirmed", true), token, 200);
        verify(speech, times(1)).synthesize(anyString(), eq(EDITED), any());
        var reversal = request("POST", revision(revision) + "/revert", Map.of("confirmed", true), token, 200);
        String reverseId = reversal.path("script_revision").path("id").asText();
        assertThat(reversal.path("script_revision").path("after_text").asText()).isEqualTo(ORIGINAL);
        assertThat(getQueue().path("segments").get(0).path("script").asText()).isEqualTo(EDITED);
        request("POST", revision(reverseId) + "/accept", Map.of("confirmed", true), token, 200);
        awaitComplete();
        assertThat(getQueue().path("segments").get(0).path("script").asText()).isEqualTo(ORIGINAL);
        verify(speech, times(1)).synthesize(anyString(), eq(ORIGINAL), any());
        var accepted = request("GET", base() + "/script-revisions", null, token, 200);
        assertThat(accepted.path("revisions").size()).isEqualTo(2);
        assertThat(accepted.path("revisions").get(0).path("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test void naturalEditingIsDurableAndCannotBeConfirmedByAnotherUserOrReplayedAfterRejection() throws Exception {
        when(reviser.rewriteWhole(ORIGINAL, "缩短刚才的讲稿")).thenReturn(EDITED);
        String key = UUID.randomUUID().toString();
        var body = Map.of("instruction", "缩短刚才的讲稿", "request_id", key, "project_id", project, "previous_queue_id", queue);
        var staged = request("POST", "/api/v1/audio/requests", body, token, 200);
        assertThat(staged.path("action").asText()).isEqualTo("REVIEW");
        assertThat(staged.path("queue_id").asText()).isEqualTo(queue);
        String revision = staged.path("script_revision").path("id").asText();
        long stranger = ((Number) records.createUser("stranger-" + UUID.randomUUID().toString().substring(0, 16), "test-only").get("id")).longValue();
        request("POST", revision(revision) + "/accept", Map.of("confirmed", true), records.issueToken(stranger), 404);
        request("POST", revision(revision) + "/reject", Map.of("confirmed", true), token, 200);
        var replay = request("POST", "/api/v1/audio/requests", body, token, 200);
        assertThat(replay.path("script_revision").path("id").asText()).isEqualTo(revision);
        assertThat(replay.path("script_revision").path("status").asText()).isEqualTo("REJECTED");
        assertThat(getQueue().path("segments").get(0).path("script").asText()).isEqualTo(ORIGINAL);
        verify(reviser, times(1)).rewriteWhole(ORIGINAL, "缩短刚才的讲稿");
        verify(speech, never()).synthesize(anyString(), anyString(), any());
    }

    @Test void aStaleProposalIsCommittedAsStaleInsteadOfOverwritingTheConfirmedScript() throws Exception {
        var first = request("POST", base() + "/segments/1/revise", Map.of("text", EDITED), token, 200);
        String pending = first.path("script_revision").path("id").asText();
        String alternate = "这是另一份经过确认的中文讲稿，保留夜空的故事，也介绍了月球。";
        var second = request("POST", base() + "/segments/1/revise", Map.of("text", alternate), token, 200);
        request("POST", revision(second.path("script_revision").path("id").asText()) + "/accept", Map.of("confirmed", true), token, 200);
        awaitComplete();
        request("POST", revision(pending) + "/accept", Map.of("confirmed", true), token, 409);
        assertThat(getQueue().path("segments").get(0).path("script").asText()).isEqualTo(alternate);
        var proposals = request("GET", base() + "/script-revisions", null, token, 200);
        boolean stale = false;
        for (JsonNode item : proposals.path("revisions"))
            if (pending.equals(item.path("id").asText())) stale = "STALE".equals(item.path("status").asText());
        assertThat(stale).isTrue();
        verify(speech, times(1)).synthesize(anyString(), eq(alternate), any());
        verify(speech, never()).synthesize(anyString(), eq(EDITED), any());
    }

    @Test void anEnglishTenSegmentAudioCanBeRewrittenAsChineseProseReviewedAcceptedAndReverted() throws Exception {
        String english = prepareTenEnglishSegments("clone-test-bilingual");
        when(reviser.rewriteWhole(english, CHINESE_REWRITE)).thenReturn(CHINESE_PROSE);
        when(speech.selectVoice("clone-test-bilingual", "zh")).thenReturn("clone-test-bilingual");
        when(speech.selectVoice("clone-test-bilingual", "en")).thenReturn("clone-test-bilingual");
        JsonNode before = getQueue();
        assertThat(before.path("segments").size()).isEqualTo(10);
        assertThat(before.path("segments").get(0).path("status").asText()).isEqualTo("READY");
        assertThat(before.path("segments").get(9).path("status").asText()).isEqualTo("PENDING");
        String oldAudio = before.path("segments").get(0).path("audio_id").asText();

        var staged = request("POST", "/api/v1/audio/requests", Map.of("instruction", CHINESE_REWRITE,
                "request_id", UUID.randomUUID().toString(), "project_id", project, "previous_queue_id", queue), token, 202);

        assertThat(staged.path("action").asText()).isEqualTo("REVIEW");
        assertThat(staged.path("queue_id").asText()).isEqualTo(queue);
        JsonNode proposal = staged.path("script_revision");
        String id = proposal.path("id").asText();
        String chinese = proposal.path("after_text").asText();
        assertThat(proposal.path("kind").asText()).isEqualTo("REWRITE");
        assertThat(proposal.path("status").asText()).isEqualTo("PENDING");
        assertThat(proposal.path("before_text").asText()).isEqualTo(english);
        assertThat(chinese.replaceAll("\\s+", "")).isEqualTo(CHINESE_PROSE.replaceAll("\\s+", ""));
        assertThat(chinese).doesNotContainPattern("[A-Za-z]");
        assertThat(queues.scriptForUser(user, queue)).isEqualTo(english);
        assertThat(getQueue().path("segments").get(0).path("audio_id").asText()).isEqualTo(oldAudio);
        verify(speech, never()).synthesize(anyString(), anyString(), any());

        request("POST", revision(id) + "/accept", Map.of("confirmed", true), token, 200);
        awaitFirstSegmentReady();

        assertThat(queues.scriptForUser(user, queue)).isEqualTo(chinese);
        assertThat(queues.commandForUser(user, queue).context()).containsEntry("language", "zh")
                .containsEntry("exact_source", false).containsEntry("script_modified", true);
        String firstChinese = getQueue().path("segments").get(0).path("script").asText();
        assertThat(SpeechService.detectLanguage(firstChinese)).isEqualTo("zh");
        verify(speech).selectVoice("clone-test-bilingual", "zh");
        verify(speech, times(1)).synthesize(anyString(), eq(firstChinese), eq("clone-test-bilingual"));
        request("POST", revision(id) + "/accept", Map.of("confirmed", true), token, 200);
        verify(speech, times(1)).synthesize(anyString(), anyString(), any());

        var rollback = request("POST", revision(id) + "/revert", Map.of("confirmed", true), token, 200);
        String rollbackId = rollback.path("script_revision").path("id").asText();
        assertThat(rollback.path("script_revision").path("before_text").asText()).isEqualTo(chinese);
        assertThat(rollback.path("script_revision").path("after_text").asText()).isEqualTo(english);
        assertThat(queues.scriptForUser(user, queue)).isEqualTo(chinese);
        verify(speech, times(1)).synthesize(anyString(), anyString(), any());
        request("POST", revision(rollbackId) + "/accept", Map.of("confirmed", true), token, 200);
        awaitFirstSegmentReady();

        assertThat(queues.scriptForUser(user, queue)).isEqualTo(english);
        assertThat(queues.commandForUser(user, queue).context()).containsEntry("language", "en");
        assertThat(getQueue().path("segments").size()).isEqualTo(10);
        verify(speech).selectVoice("clone-test-bilingual", "en");
        verify(speech, times(1)).synthesize(anyString(), eq(english.split("\\n\\n")[0]), eq("clone-test-bilingual"));
    }

    @Test void aVoiceWithoutChineseSupportKeepsTheProposalUntilTheUserConfirmsASupportedVoice() throws Exception {
        String english = prepareTenEnglishSegments("english-only");
        when(reviser.rewriteWhole(english, CHINESE_REWRITE)).thenReturn(CHINESE_PROSE);
        when(speech.selectVoice("english-only", "zh")).thenThrow(new com.bedouyu.audiorag.common.ApiException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "所选音色不支持请求的语言：zh"));
        when(speech.selectVoice("chinese-voice", "zh")).thenReturn("chinese-voice");
        JsonNode before = getQueue();
        String oldAudio = before.path("segments").get(0).path("audio_id").asText();

        var staged = request("POST", base() + "/rewrite", Map.of("instruction", CHINESE_REWRITE), token, 200);
        String id = staged.path("script_revision").path("id").asText();
        request("POST", revision(id) + "/accept", Map.of("confirmed", true), token, 400);

        assertThat(queues.scriptForUser(user, queue)).isEqualTo(english);
        assertThat(getQueue().path("segments").get(0).path("audio_id").asText()).isEqualTo(oldAudio);
        var proposals = request("GET", base() + "/script-revisions", null, token, 200);
        assertThat(proposals.path("revisions").get(0).path("id").asText()).isEqualTo(id);
        assertThat(proposals.path("revisions").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(queues.commandForUser(user, queue).voice()).isEqualTo("english-only");
        assertThat(queues.commandForUser(user, queue).context()).containsEntry("language", "en").containsEntry("exact_source", false);
        verify(speech, never()).synthesize(anyString(), anyString(), any());

        request("POST", revision(id) + "/accept", Map.of("confirmed", true, "voice", "chinese-voice"), token, 200);
        awaitFirstSegmentReady();

        assertThat(queues.scriptForUser(user, queue)).isEqualTo(staged.path("script_revision").path("after_text").asText());
        assertThat(queues.commandForUser(user, queue).voice()).isEqualTo("chinese-voice");
        assertThat(queues.commandForUser(user, queue).context()).containsEntry("language", "zh")
                .containsEntry("exact_source", false).containsEntry("script_modified", true);
        String firstChinese = getQueue().path("segments").get(0).path("script").asText();
        verify(speech).selectVoice("chinese-voice", "zh");
        verify(speech, times(1)).synthesize(anyString(), eq(firstChinese), eq("chinese-voice"));
    }

    @Test void anUnrequestedLanguageSwitchCannotCreateAWholeScriptProposal() throws Exception {
        String english = prepareTenEnglishSegments("clone-test-bilingual");
        String instruction = "让当前讲稿更生动一些";
        when(reviser.rewriteWhole(english, instruction)).thenReturn(CHINESE_PROSE);

        request("POST", base() + "/rewrite", Map.of("instruction", instruction), token, 400);

        assertThat(queues.scriptForUser(user, queue)).isEqualTo(english);
        assertThat(request("GET", base() + "/script-revisions", null, token, 200).path("revisions").size()).isZero();
        verify(speech, never()).synthesize(anyString(), anyString(), any());
    }

    private String prepareTenEnglishSegments(String voice) {
        List<String> scripts = java.util.stream.IntStream.rangeClosed(1, 10).mapToObj(number ->
                "Chapter " + number + ". The old courtyard wakes in the morning light. A gentle breeze carries the scent of spring, "
                        + "and the familiar wooden door opens into a quiet memory of home.").toList();
        String full = String.join("\n\n", scripts);
        var command = new ContinuousAudioService.QueueCommand("The courtyard", project, 10, 1, null, voice, 1,
                UUID.randomUUID().toString(), "CONTINUOUS", "English reading", null, null, Map.of("language", "en", "exact_source", false));
        var creation = queues.reserve(user, command);
        queue = creation.queueId();
        queues.prepared(user, creation, new CoursePlan("The courtyard", full, List.of(), 0), scripts);
        String generation = queues.claimGeneration(user, queue).orElseThrow();
        for (int i = 0; i < 3; i++) {
            var claim = queues.claimNext(user, queue, generation).orElseThrow();
            queues.complete(user, claim, "/media/" + claim.audioId() + ".wav");
        }
        queues.releaseGeneration(user, queue, generation);
        return full;
    }

    private void awaitFirstSegmentReady() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (true) {
                var state = queues.status(user, queue);
                assertThat(state.get("status")).as(state.toString()).isNotIn("FAILED", "NEEDS_REVIEW");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> segments = (List<Map<String, Object>>) state.get("segments");
                if (!"GENERATING".equals(state.get("status")) && "READY".equals(segments.get(0).get("status"))) return;
                Thread.sleep(15);
            }
        });
    }

    private String base() { return "/api/v1/continuous-audio/" + queue; }
    private String revision(String id) { return base() + "/script-revisions/" + id; }
    private JsonNode getQueue() throws Exception { return request("GET", base(), null, token, 200); }
    private void awaitComplete() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            while (true) {
                var state = queues.status(user, queue);
                assertThat(state.get("status")).as(state.toString()).isNotIn("FAILED", "NEEDS_REVIEW");
                if ("COMPLETE".equals(state.get("status"))) return;
                Thread.sleep(15);
            }
        });
    }
    private JsonNode request(String method, String path, Map<String, ?> body, String auth, int expected) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Bearer " + auth).header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }
}
