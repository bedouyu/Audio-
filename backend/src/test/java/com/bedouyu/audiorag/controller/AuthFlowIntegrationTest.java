package com.bedouyu.audiorag.controller;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.common.PasswordHasher;
import com.bedouyu.audiorag.model.entity.AuthToken;
import com.bedouyu.audiorag.model.entity.UserAccount;
import com.bedouyu.audiorag.rag.OnnxEmbeddingService;
import com.bedouyu.audiorag.rag.QdrantService;
import com.bedouyu.audiorag.repository.AuthTokenRepository;
import com.bedouyu.audiorag.repository.UserAccountRepository;
import com.bedouyu.audiorag.service.ContinuousAudioService;
import com.bedouyu.audiorag.tools.audio.SpeechService;
import com.bedouyu.audiorag.tools.research.WebSearchService;
import com.bedouyu.audiorag.tools.storage.MinioStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=",
        "spring.datasource.url=jdbc:h2:mem:auth_flow;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "audiorag.llm.api-key="
})
class AuthFlowIntegrationTest {
    @Autowired ObjectMapper json;
    @Autowired UserAccountRepository users;
    @Autowired AuthTokenRepository tokens;
    @MockitoBean QdrantService qdrant;
    @MockitoBean OnnxEmbeddingService embeddings;
    @MockitoBean MinioStorageService minio;
    @MockitoBean SpeechService speech;
    @MockitoBean WebSearchService web;
    @MockitoBean ContinuousAudioService continuous;
    @Value("${local.server.port}") int port;

    private final HttpClient client = HttpClient.newHttpClient();

    @Test void registerThenLoginThenMeKeepsTheSameAccount() throws Exception {
        String name = name();
        var registered = register(name, "password1");
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(200);
        assertThat(registered.body()).contains("\"token\"");
        assertThat(registered.body()).doesNotContain("password");

        var loggedIn = call("POST", "/api/v1/auth/login", null, Map.of("username", name, "password", "password1"));
        assertThat(loggedIn.statusCode()).as(loggedIn.body()).isEqualTo(200);
        String token = token(loggedIn.body());

        var me = call("GET", "/api/v1/me", token, null);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(json.readTree(me.body()).path("username").asText()).isEqualTo(name);
    }

    @Test void wrongPasswordAndUnknownUserBothAnswerUnauthorizedWithoutLeakingWhichOne() throws Exception {
        String name = name();
        register(name, "password1");

        var wrongPassword = call("POST", "/api/v1/auth/login", null, Map.of("username", name, "password", "password2"));
        var unknownUser = call("POST", "/api/v1/auth/login", null, Map.of("username", name() + "x", "password", "password1"));

        assertThat(wrongPassword.statusCode()).isEqualTo(401);
        assertThat(unknownUser.statusCode()).isEqualTo(401);
        assertThat(message(wrongPassword.body())).isEqualTo(message(unknownUser.body()));
    }

    @Test void duplicateUsernameIsAConflictRatherThanAnOverwrite() throws Exception {
        String name = name();
        assertThat(register(name, "password1").statusCode()).isEqualTo(200);

        var again = register(name, "password2");

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(call("POST", "/api/v1/auth/login", null, Map.of("username", name, "password", "password2")).statusCode())
                .isEqualTo(401);
    }

    @Test void logoutRevokesTheTokenAndStaysIdempotent() throws Exception {
        String token = token(register(name(), "password1").body());
        assertThat(call("GET", "/api/v1/me", token, null).statusCode()).isEqualTo(200);

        assertThat(call("POST", "/api/v1/auth/logout", token, null).statusCode()).isEqualTo(200);
        assertThat(call("GET", "/api/v1/me", token, null).statusCode()).isEqualTo(401);

        assertThat(call("POST", "/api/v1/auth/logout", token, null).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/v1/auth/logout", null, null).statusCode()).isEqualTo(200);
    }

    @Test void expiredTokenIsRejectedEvenWhenItIsInTheDatabase() throws Exception {
        String name = name();
        long userId = json.readTree(register(name, "password1").body()).path("user").path("id").asLong();
        String raw = "expired-" + UUID.randomUUID();
        tokens.save(new AuthToken(Hashing.sha256(raw), userId, LocalDateTime.now().minusMinutes(1)));

        assertThat(call("GET", "/api/v1/me", raw, null).statusCode()).isEqualTo(401);
    }

    @Test void legacyUnsaltedPasswordIsUpgradedOnSuccessfulLogin() throws Exception {
        String name = name();
        users.saveAndFlush(new UserAccount(name, Hashing.sha256("password1")));

        var login = call("POST", "/api/v1/auth/login", null, Map.of("username", name, "password", "password1"));
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);

        String upgraded = users.findByUsername(name).orElseThrow().getPasswordHash();
        assertThat(upgraded).startsWith("pbkdf2$");
        assertThat(PasswordHasher.verify("password1", upgraded)).isTrue();
    }

    private HttpResponse<String> register(String name, String password) throws Exception {
        return call("POST", "/api/v1/auth/register", null, Map.of("username", name, "password", password));
    }

    private HttpResponse<String> call(String method, String path, String token, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(String body) {
        return json.readTree(body).path("token").asText();
    }

    private String message(String body) {
        String detail = json.readTree(body).path("detail").asText("");
        return detail.isEmpty() ? json.readTree(body).path("message").asText("") : detail;
    }

    private String name() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }
}
