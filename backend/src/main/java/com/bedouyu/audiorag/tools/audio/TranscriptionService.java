package com.bedouyu.audiorag.tools.audio;

import com.bedouyu.audiorag.common.ApiException;
import tools.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Service
public class TranscriptionService {
    private final RestClient client;
    private final String url;
    private final String apiKey;
    private final String model;

    public TranscriptionService(RestClient.Builder builder,
            @Value("${audiorag.transcription.url}") String url,
            @Value("${audiorag.transcription.api-key}") String apiKey,
            @Value("${audiorag.transcription.model}") String model) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(5000);
        requests.setReadTimeout(120000);
        this.client = builder.requestFactory(requests).build();
        this.url = url;
        this.apiKey = apiKey;
        this.model = model;
    }

    public String transcribe(String filename, byte[] audio) {
        if (!configured()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "音频自动转写尚未配置；请填写转写文本，或配置 AUDIORAG_TRANSCRIPTION_URL 和密钥");
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("model", model);
        body.add("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        JsonNode response = client.post().uri(url)
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA).body(body)
                .retrieve().body(JsonNode.class);
        String text = response == null ? "" : response.path("text").asText("").trim();
        if (text.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "转写服务没有返回文字");
        }
        return text;
    }

    public boolean configured() {
        return !url.isBlank() && !apiKey.isBlank();
    }
}
