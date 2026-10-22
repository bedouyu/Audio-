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
import org.springframework.http.client.ClientHttpResponse;
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
                            throw synthesisRejected(response);
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

    /**
     * 录入一个零样本音色：服务端只需对样例做一次特征提取并缓存，不训练模型。
     * 请求体包含 base64 样例 WAV、样例文字与语言。
     */
    Map<String, Object> importVoice(Map<String, Object> request) {
        try {
            byte[] body = JSON.writeValueAsBytes(request);
            JsonNode response = control.post().uri("/voices").contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON).contentLength(body.length).body(body)
                    .retrieve().body(JsonNode.class);
            if (response == null || !response.path("name").isTextual()) throw invalidInventory();
            var voice = new LinkedHashMap<String, Object>();
            voice.put("name", response.path("name").asText());
            voice.put("display_name", response.path("display_name").asText(response.path("name").asText()));
            voice.put("provider", "cosyvoice");
            voice.put("enabled", true);
            voice.put("languages", JSON.convertValue(response.path("languages"), List.class));
            return voice;
        } catch (RestClientResponseException failure) {
            throw rejected(failure, "录入音色");
        } catch (ResourceAccessException unavailable) {
            throw unavailable();
        } catch (RestClientException malformed) {
            throw invalidInventory();
        }
    }

    void deleteVoice(String name) {
        try {
            control.delete().uri("/voices/{name}", name).accept(MediaType.APPLICATION_JSON)
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException failure) {
            throw rejected(failure, "删除音色");
        } catch (ResourceAccessException unavailable) {
            throw unavailable();
        }
    }

    /** 把适配器的拒绝原因原样带回前端，避免只显示一个 HTTP 状态码。 */
    private static ApiException rejected(RestClientResponseException failure, String action) {
        String detail = "";
        try {
            detail = JSON.readTree(failure.getResponseBodyAsString()).path("detail").asText("");
        } catch (RuntimeException ignored) {
            // 响应体不是 JSON 时退回通用提示。
        }
        int status = failure.getStatusCode().value();
        if (status == 409) return new ApiException(HttpStatus.CONFLICT,
                detail.isBlank() ? action + "失败：名称已存在" : detail);
        if (status == 400 || status == 413 || status == 422) return new ApiException(HttpStatus.BAD_REQUEST,
                detail.isBlank() ? action + "失败：样例不符合要求" : detail);
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "本地语音服务暂时不可用（HTTP " + status + "），" + action + "未完成");
    }

    /** 适配器会说明拒绝原因（例如音色不支持该语言），原样带给用户，而不是只留一个状态码。 */
    private static ApiException synthesisRejected(ClientHttpResponse response) throws IOException {
        String body = "";
        try (var input = response.getBody()) {
            body = new String(input.readNBytes(4096), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            // 读不到正文时退回通用提示
        }
        String detail = "";
        try {
            detail = JSON.readTree(body).path("detail").asText("");
        } catch (RuntimeException notJson) {
            // 正文不是 JSON
        }
        int status = response.getStatusCode().value();
        if (detail.isBlank()) return serviceFailure(status);
        return new ApiException(status == 409 ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST, detail);
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
