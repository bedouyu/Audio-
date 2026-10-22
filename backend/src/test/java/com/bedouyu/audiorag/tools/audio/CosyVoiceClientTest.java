package com.bedouyu.audiorag.tools.audio;

import com.bedouyu.audiorag.common.ApiException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class CosyVoiceClientTest {
    private static final String BASE = "http://127.0.0.1:50000";
    private static final String ID = "b6bcd776-7483-4425-9598-cf4af944b60a";
    private static final String VOICES = """
            {"voices":[{"name":"cosyvoice-official-demo","display_name":"官方示例音色","locale":"bilingual",
            "culture":"bilingual","languages":["zh","en"],"enabled":true}]}
            """;
    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final RestClient rest = builder.build();
    private final CosyVoiceClient client = new CosyVoiceClient(rest, rest);
    @TempDir Path directory;

    @Test void realHttpTransportSendsExactUtf8LengthWithoutChunkedTransfer() throws Exception {
        var transferEncoding = new AtomicReference<String>();
        var contentLength = new AtomicReference<String>();
        var received = new AtomicReference<byte[]>();
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/synthesize", exchange -> {
            transferEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));
            contentLength.set(exchange.getRequestHeaders().getFirst("Content-Length"));
            if (transferEncoding.get() != null || contentLength.get() == null) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            received.set(exchange.getRequestBody().readAllBytes());
            byte[] audio = wave();
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, audio.length);
            try (var output = exchange.getResponseBody()) { output.write(audio); }
            exchange.close();
        });
        local.start();
        try {
            var actual = new CosyVoiceClient(RestClient.builder(), "http://127.0.0.1:" + local.getAddress().getPort(), 5);
            Path target = directory.resolve("real-transport.wav");
            actual.synthesize("你好，hello", "cosyvoice-official-demo", "bilingual", target);
            assertThat(transferEncoding.get()).isNull();
            assertThat(received.get()).hasSize(Integer.parseInt(contentLength.get()));
            assertThat(new String(received.get(), StandardCharsets.UTF_8)).contains("你好，hello");
            assertThat(Files.readAllBytes(target)).isEqualTo(wave());
        } finally {
            local.stop(0);
        }
    }

    @Test void realInventoryPreservesDisplayNameAndMultilingualVoiceCapabilities() {
        expectVoices();
        var inventory = client.voices();
        assertThat(inventory).hasSize(1);
        assertThat(inventory.get(0)).containsEntry("display_name", "官方示例音色")
                .containsEntry("provider", "cosyvoice").containsEntry("languages", List.of("zh", "en"));
        assertThat(SpeechService.selectVoice(inventory, "", "zh")).isEqualTo("cosyvoice-official-demo");
        assertThat(SpeechService.selectVoice(inventory, "", "en")).isEqualTo("cosyvoice-official-demo");
        assertThat(SpeechService.selectVoice(inventory, "cosyvoice-official-demo", "bilingual"))
                .isEqualTo("cosyvoice-official-demo");
        assertThatThrownBy(() -> SpeechService.selectVoice(inventory, "", "fr")).isInstanceOf(ApiException.class);
        server.verify();
    }

    @Test void configuredProviderPublishesVerifiedWaveAtExistingMediaContract() throws IOException {
        expectVoices();
        server.expect(requestTo(BASE + "/synthesize")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {"text":"An English story","voice":"cosyvoice-official-demo","language":"en"}
                        """))
                .andRespond(withSuccess(wave(), MediaType.parseMediaType("audio/wav")));
        SpeechService speech = speech();
        assertThat(speech.synthesize(ID, "An English story", "")).isEqualTo("/media/" + ID + ".wav");
        WavVerifier.validate(speech.audioFile(ID + ".wav"));
        assertThat(Files.readAllBytes(directory.resolve(ID + ".wav"))).isEqualTo(wave());
        try (var files = Files.list(directory)) { assertThat(files).hasSize(1); }
        server.verify();
    }

    @Test void invalidReturnedAudioDoesNotReplaceRetainedAudioOrLeavePartialFiles() throws IOException {
        Files.write(directory.resolve(ID + ".wav"), wave());
        expectVoices();
        server.expect(requestTo(BASE + "/synthesize"))
                .andRespond(withSuccess("{\"detail\":\"private-provider-body\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> speech().synthesize(ID, "你好", ""))
                .isInstanceOf(ApiException.class).hasMessageContaining("校验失败").hasMessageNotContaining("private-provider-body");
        assertThat(Files.readAllBytes(directory.resolve(ID + ".wav"))).isEqualTo(wave());
        try (var files = Files.list(directory)) { assertThat(files).hasSize(1); }
        server.verify();
    }

    @Test void truncatedWaveIsNotPublished() throws IOException {
        expectVoices();
        byte[] incomplete = java.util.Arrays.copyOf(wave(), wave().length - 2);
        server.expect(requestTo(BASE + "/synthesize"))
                .andRespond(withSuccess(incomplete, MediaType.parseMediaType("audio/wav")));
        assertThatThrownBy(() -> speech().synthesize(ID, "你好", "")).isInstanceOf(ApiException.class);
        try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
        server.verify();
    }

    @Test void unavailableCosyVoiceDoesNotFallBackToSystemSpeechOrExposeResponse() throws IOException {
        server.expect(requestTo(BASE + "/voices")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .body("private-provider-path-and-traceback"));
        assertThatThrownBy(() -> speech().voices()).isInstanceOf(ApiException.class)
                .hasMessageContaining("CosyVoice").hasMessageNotContaining("private-provider-path-and-traceback")
                .hasMessageNotContaining("脚本不存在");
        server.verify();
    }

    @Test void busySynthesisIsReportedOnceWithoutPublishingOrRepeatingRequest() throws IOException {
        expectVoices();
        server.expect(requestTo(BASE + "/synthesize"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("private-busy-body"));
        assertThatThrownBy(() -> speech().synthesize(ID, "你好", ""))
                .isInstanceOf(ApiException.class).hasMessageContaining("正忙").hasMessageNotContaining("private-busy-body");
        try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
        server.verify();
    }

    @Test void transportTimeoutDoesNotRetryOrLeaveAFileClaimingSuccess() throws IOException {
        expectVoices();
        server.expect(requestTo(BASE + "/synthesize"))
                .andRespond(withException(new SocketTimeoutException("private-network-details")));
        assertThatThrownBy(() -> speech().synthesize(ID, "你好", ""))
                .isInstanceOf(ApiException.class).hasMessageContaining("结果未确认")
                .hasMessageNotContaining("private-network-details");
        try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
        server.verify();
    }

    @Test void unknownVoiceFailsBeforeSynthesisOrCreatingOutput() throws IOException {
        expectVoices();
        assertThatThrownBy(() -> speech().synthesize(ID, "你好", "missing"))
                .isInstanceOf(ApiException.class).hasMessageContaining("音色未安装或不可用");
        try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
        server.verify();
    }

    @Test void malformedVoiceInventoryIsNotInventedOrInterpretedAsSuccess() {
        server.expect(requestTo(BASE + "/voices"))
                .andRespond(withSuccess("{\"voices\":[{\"enabled\":true}]}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::voices).isInstanceOf(ApiException.class).hasMessageContaining("有效音色列表");
        server.verify();
    }

    @Test void localEndpointConfigurationCannotPointToRemoteOrAuthenticatedAddresses() {
        assertThat(CosyVoiceClient.localBaseUrl(BASE + "/")).isEqualTo(BASE);
        assertThat(CosyVoiceClient.localBaseUrl("http://localhost:50000")).isEqualTo("http://localhost:50000");
        assertThat(CosyVoiceClient.localBaseUrl("http://[::1]:50000")).isEqualTo("http://[::1]:50000");
        for (String invalid : List.of("https://127.0.0.1:50000", "http://example.test:50000", "http://user:pass@localhost:50000",
                BASE + "/other", BASE + "?token=private", BASE + "#fragment", "http://localhost:0"))
            assertThatIllegalArgumentException().as(invalid).isThrownBy(() -> CosyVoiceClient.localBaseUrl(invalid));
    }

    @Test void unsupportedProviderFailsConfigurationInsteadOfSilentlyUsingSystemSpeech() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SpeechService(directory.toString(), "missing.ps1",
                "unsupported", BASE, 600, RestClient.builder()));
    }

    private SpeechService speech() throws IOException {
        return new SpeechService(directory.toString(), directory.resolve("missing.ps1").toString(), client);
    }

    private void expectVoices() {
        server.expect(requestTo(BASE + "/voices")).andRespond(withSuccess(VOICES, MediaType.APPLICATION_JSON));
    }

    private static byte[] wave() {
        var bytes = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(44)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(24000).putInt(48000)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(8).putLong(0);
        return bytes.array();
    }
}
