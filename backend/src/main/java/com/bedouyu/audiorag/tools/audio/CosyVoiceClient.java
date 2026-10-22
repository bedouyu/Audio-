package com.bedouyu.audiorag.tools.audio;

import com.bedouyu.audiorag.common.ApiException;
import java.io.IOException;
import java.net.Proxy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** HTTP adapter for the locally deployed CosyVoice service; no cloud calls or automatic retries. */
final class CosyVoiceClient {
    private static final long MAX_AUDIO_BYTES = 256L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final RestClient control;
    private final RestClient synthesis;

    CosyVoiceClient(RestClient.Builder builder, String baseUrl, int timeoutSeconds) {
        String base = localBaseUrl(baseUrl);
        if (timeoutSeconds < 1 || timeoutSeconds > 1800)
            throw new IllegalArgumentException("CosyVoice 合成超时须为 1 至 1800 秒");
        this.control = builder.clone().baseUrl(base).requestFactory(requestFactory(5000)).build();
        this.synthesis = builder.clone().baseUrl(base).requestFactory(requestFactory(timeoutSeconds * 1000)).build();
    }

    CosyVoiceClient(RestClient control, RestClient synthesis) {
        this.control = control;
        this.synthesis = synthesis;
    }

    private static SimpleClientHttpRequestFactory requestFactory(int readTimeoutMillis) {
        var requests = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        requests.setConnectTimeout(2000);
        requests.setReadTimeout(readTimeoutMillis);
        requests.setProxy(Proxy.NO_PROXY);
        return requests;
    }

    static String localBaseUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"http".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !List.of("127.0.0.1", "localhost", "[::1]", "::1").contains(host.toLowerCase(java.util.Locale.ROOT))
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath())))
                throw new IllegalArgumentException();
            return value.replaceAll("/+$", "");
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IllegalArgumentException("CosyVoice 地址必须是本机 HTTP 服务地址");
        }
    }

    List<Map<String, Object>> voices() {
        try {
            JsonNode response = control.get().uri("/voices").accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(JsonNode.class);
            JsonNode inventory = response == null ? null : response.get("voices");
            if (inventory == null || !inventory.isArray()) throw invalidInventory();
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode entry : inventory) {
                String name = entry.path("name").asText("").strip();
                String locale = entry.path("locale").asText("").strip();
                if (name.isBlank() || locale.isBlank()) throw invalidInventory();
                Map<String, Object> voice = new LinkedHashMap<>();
                voice.put("name", name);
                voice.put("display_name", entry.path("display_name").asText(name));
                voice.put("locale", locale);
                voice.put("culture", locale);
                voice.put("enabled", entry.path("enabled").asBoolean(false));
                voice.put("provider", "cosyvoice");
                List<String> languages = new ArrayList<>();
                if (entry.path("languages").isArray())
                    for (JsonNode language : entry.path("languages"))
                        if (language.isTextual() && !language.asText().isBlank()) languages.add(language.asText());
                voice.put("languages", List.copyOf(languages));
                result.add(voice);
            }
            return result;
        } catch (RestClientResponseException failure) {
            throw serviceFailure(failure.getStatusCode().value());
        } catch (ResourceAccessException unavailable) {
            throw unavailable();
        } catch (RestClientException malformed) {
            throw invalidInventory();
        }
    }

    synchronized void synthesize(String text, String voice, String language, Path target) throws IOException {
        try {
            // The local adapter requires a bounded request body rather than HTTP chunked transfer.
            byte[] body = JSON.writeValueAsBytes(Map.of("text", text, "voice", voice, "language", language));
            synthesis.post().uri("/synthesize").contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.parseMediaType("audio/wav"))
                    .contentLength(body.length).body(body)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful())
                            throw serviceFailure(response.getStatusCode().value());
                        long declared = response.getHeaders().getContentLength();
                        if (declared > MAX_AUDIO_BYTES) throw new IOException("CosyVoice 音频超过单次大小限制");
                        try (var input = response.getBody(); var output = Files.newOutputStream(target)) {
                            byte[] buffer = new byte[16 * 1024];
                            long total = 0;
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                total += count;
                                if (total > MAX_AUDIO_BYTES) throw new IOException("CosyVoice 音频超过单次大小限制");
                                output.write(buffer, 0, count);
                            }
                            if (declared >= 0 && total != declared) throw new IOException("CosyVoice 音频传输不完整");
                        }
                        return null;
                    });
            WavVerifier.validate(target);
        } catch (ResourceAccessException unavailable) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "CosyVoice 连接或合成等待超时，本次结果未确认，请核验任务状态后再试");
        } catch (RestClientException failure) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CosyVoice 未返回可用音频，请检查本地语音服务");
        }
    }

    private static ApiException serviceFailure(int status) {
        if (status == 400 || status == 422)
            return new ApiException(HttpStatus.BAD_REQUEST, "CosyVoice 拒绝了合成参数，请检查文本、语言和音色");
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "CosyVoice 本地语音服务未就绪、正忙或暂不可用（HTTP " + status + "）");
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "无法连接 CosyVoice 本地语音服务，请先启动语音服务");
    }

    private static ApiException invalidInventory() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CosyVoice 未返回有效音色列表，请检查本地语音服务");
    }
}
