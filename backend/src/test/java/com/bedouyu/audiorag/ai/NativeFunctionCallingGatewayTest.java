package com.bedouyu.audiorag.ai;

import com.bedouyu.audiorag.model.dto.*;
import com.bedouyu.audiorag.tools.ToolSchemas;
import com.bedouyu.audiorag.ai.token.ConservativeTokenCounter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class NativeFunctionCallingGatewayTest {
    @Test
    void assemblesNativeToolFragmentsAndReplaysIdsAndReasoning() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicReference<JsonNode> requestBody = new AtomicReference<>();
        String stream = "data: " + json.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of(
                "reasoning_content", "private protocol data", "tool_calls", List.of(Map.of("index", 0, "id", "call_1",
                "function", Map.of("name", "knowledge_", "arguments", "{\"query\":")))))))) + "\n\n";
        stream += "data: " + json.writeValueAsString(Map.of("choices", List.of(Map.of("delta", Map.of(
                "tool_calls", List.of(Map.of("index", 0, "function", Map.of("name", "search", "arguments", "\"概率\"}")))),
                "finish_reason", "tool_calls")))) + "\n\ndata: [DONE]\n\n";
        HttpServer server = server(json, requestBody, stream);
        NativeFunctionCallingGateway gateway = new NativeFunctionCallingGateway(json,
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", "test-model", false,
                budget(json, "test-model", 100_000));
        try {
            var definition = new FunctionDefinition("knowledge_search", "搜索",
                    ToolSchemas.object(Map.of("query", ToolSchemas.string(1000)), "query"));
            var reply = gateway.decide(List.of(Map.of("role", "user", "content", "解释概率")),
                    List.of(definition), ignored -> { });
            assertThat(reply.calls()).containsExactly(new FunctionCall("call_1", "knowledge_search", "{\"query\":\"概率\"}"));
            List<Map<String, Object>> replay = List.of(reply.assistantMessage(),
                    Map.of("role", "tool", "tool_call_id", "call_1", "content", "{\"status\":\"OK\"}"));
            gateway.decide(replay, List.of(definition), ignored -> { });
            assertThat(requestBody.get().path("tools").path(0).path("function").path("name").asText()).isEqualTo("knowledge_search");
            assertThat(requestBody.get().path("messages").path(0).path("reasoning_content").asText()).isEqualTo("private protocol data");
            assertThat(requestBody.get().path("messages").path(1).path("tool_call_id").asText()).isEqualTo("call_1");
        } finally { gateway.close(); server.stop(0); }
    }
    @Test
    void neverExecutesIncompleteToolArguments() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String partial = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"knowledge_search\",\"arguments\":\"{\"}}]}}]}\n\n";
        HttpServer server = server(json, new AtomicReference<>(), partial);
        var gateway = gateway(json, server);
        try {
            assertThatThrownBy(() -> gateway.decide(List.of(), List.of(), ignored -> { }))
                    .hasMessageContaining("不完整");
        } finally { gateway.close(); server.stop(0); }
    }
    @Test void aggregatesTwoInterleavedToolsAndRepeatsMetadataWithoutDroppingArgumentDeltas() throws Exception {
        String stream = event(Map.of("tool_calls", List.of(
                tool(1, "call_b", "knowledge_search", "{\"query\":\""),
                tool(0, "call_a", "memory_read", "{\"id\":\""))), null);
        stream += event(Map.of("tool_calls", List.of(tool(0, "call_a", "memory_read", "a"))), null);
        stream += event(Map.of("tool_calls", List.of(tool(1, "call_b", "knowledge_search", "a"))), null);
        stream += event(Map.of("tool_calls", List.of(
                tool(1, "call_b", "knowledge_search", "a\"}"),
                tool(0, "call_a", "memory_read", "a\"}"))), "tool_calls");
        // EOF is compatible with a complete finish; [DONE] is not mandatory.
        var reply = decide(stream);
        assertThat(reply.calls()).containsExactly(
                new FunctionCall("call_a", "memory_read", "{\"id\":\"aa\"}"),
                new FunctionCall("call_b", "knowledge_search", "{\"query\":\"aa\"}"));
    }
    @Test void acceptsStopAtEofAndNeverStreamsPrivateReasoning() throws Exception {
        ObjectMapper json = new ObjectMapper();
        HttpServer server = server(json, new AtomicReference<>(),
                event(Map.of("content", "visible", "reasoning_content", "private"), "stop"));
        var gateway = gateway(json, server);
        List<String> tokens = new ArrayList<>();
        try {
            var reply = gateway.decide(List.of(), List.of(), tokens::add);
            assertThat(reply.content()).isEqualTo("visible");
            assertThat(reply.reasoningContent()).isEqualTo("private");
            assertThat(tokens).containsExactly("visible");
        } finally { gateway.close(); server.stop(0); }
    }
    @Test void rejectsFinishReasonsThatContradictToolCalls() throws Exception {
        assertThatThrownBy(() -> decide(event(Map.of("tool_calls", List.of(tool(0, "c", "knowledge_search", "{}"))), "stop")))
                .hasMessageContaining("不一致");
        assertThatThrownBy(() -> decide(event(Map.of(), "tool_calls"))).hasMessageContaining("没有返回工具");
        assertThatThrownBy(() -> decide(event(Map.of(), "stop") + event(Map.of(), "tool_calls")))
                .hasMessageContaining("不一致的结束原因");
        assertThatThrownBy(() -> decide(event(Map.of(), "stop") + event(Map.of("content", "late"), null)))
                .hasMessageContaining("结束后");
    }
    @Test void rejectsMissingDuplicateAndInconsistentCallIds() throws Exception {
        assertThatThrownBy(() -> decide(event(Map.of("tool_calls", List.of(
                Map.of("index", 0, "function", Map.of("name", "knowledge_search", "arguments", "{}")))), "tool_calls")))
                .hasMessageContaining("编号或名称无效");
        assertThatThrownBy(() -> decide(event(Map.of("tool_calls", List.of(
                tool(0, "duplicate", "knowledge_search", "{}"), tool(1, "duplicate", "memory_read", "{}"))), "tool_calls")))
                .hasMessageContaining("重复工具调用编号");
        assertThatThrownBy(() -> decide(event(Map.of("tool_calls", List.of(tool(0, "first", "knowledge_search", ""))), null)
                + event(Map.of("tool_calls", List.of(tool(0, "second", "", "{}"))), "tool_calls")))
                .hasMessageContaining("不一致的调用编号");
    }
    @Test void rejectsNonIntegralMissingAndOutOfRangeIndexes() throws Exception {
        for (Object index : List.of(-1, 8, 0.5, "0", Long.MAX_VALUE)) {
            assertThatThrownBy(() -> decide(event(Map.of("tool_calls", List.of(
                    Map.of("index", index, "id", "c", "function", Map.of("name", "knowledge_search")))), "tool_calls")))
                    .as("index %s", index).hasMessageContaining("序号无效");
        }
        assertThatThrownBy(() -> decide(event(Map.of("tool_calls", List.of(
                Map.of("id", "c", "function", Map.of("name", "knowledge_search")))), "tool_calls")))
                .hasMessageContaining("序号无效");
    }
    @Test void rejectsOversizeAndWrongTypeCallFields() throws Exception {
        assertThatThrownBy(() -> decide(event(Map.of("tool_calls", List.of(tool(0, "c".repeat(129), "knowledge_search", "{}"))), "tool_calls")))
                .hasMessageContaining("过长");
        assertThatThrownBy(() -> decide(event(Map.of("tool_calls", List.of(tool(0, "c", "a".repeat(65), "{}"))), "tool_calls")))
                .hasMessageContaining("过长");
        assertThatThrownBy(() -> decide(event(Map.of("tool_calls", List.of(tool(0, "c", "knowledge_search", "x".repeat(32001)))), "tool_calls")))
                .hasMessageContaining("参数过长");
        assertThatThrownBy(() -> decide(event(Map.of("tool_calls", List.of(Map.of("index", 0, "id", 123,
                "function", Map.of("name", "knowledge_search", "arguments", "{}")))), "tool_calls")))
                .hasMessageContaining("字段类型无效");
    }
    @Test void interruptClosesAnActiveStreamPromptly() throws Exception {
        ObjectMapper json = new ObjectMapper();
        CountDownLatch streaming = new CountDownLatch(1), release = new CountDownLatch(1),
                received = new CountDownLatch(1), handlerClosed = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(event(Map.of("content", "waiting"), null).getBytes(StandardCharsets.UTF_8));
                output.flush(); streaming.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } finally { handlerClosed.countDown(); }
        });
        server.start();
        var gateway = gateway(json, server);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try { gateway.decide(List.of(), List.of(), ignored -> received.countDown()); }
            catch (Throwable error) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        try {
            caller.start(); assertThat(streaming.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(received.await(3, TimeUnit.SECONDS)).as("the response body is actively being read").isTrue();
            caller.interrupt(); caller.join(2000);
            assertThat(caller.isAlive()).as("blocked model read must cancel within two seconds").isFalse();
            assertThat(failure.get()).hasMessageContaining("已取消");
            assertThat(interrupted.get()).isTrue();
            release.countDown();
            assertThat(handlerClosed.await(1, TimeUnit.SECONDS)).as("the loopback handler releases its response").isTrue();
        } finally {
            release.countDown(); gateway.close(); server.stop(0); caller.join(2000);
        }
    }
    @Test void refusesOversizedWholeRequestBeforeAnyHttpCall() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicReference<JsonNode> received = new AtomicReference<>();
        HttpServer server = server(json, received, event(Map.of("content", "ok"), "stop"));
        var gateway = new NativeFunctionCallingGateway(json, "http://127.0.0.1:" + server.getAddress().getPort(),
                "secret-test-key", "test", false, budget(json, "test", 4096));
        try {
            var schema = new FunctionDefinition("read_source", "tool secret " + "汉".repeat(2000), Map.of());
            assertThatThrownBy(() -> gateway.decide(List.of(Map.of("role", "system", "content", "constraint")),
                    List.of(schema), ignored -> { })).isInstanceOf(ModelContextBudgetException.class);
            assertThat(received.get()).isNull();
            assertThat(gateway.lastObservation()).containsKey("budget").doesNotContainKey("usage");
            assertThat(json.writeValueAsString(gateway.lastObservation())).doesNotContain("secret-test-key", "tool secret");
        } finally { gateway.close(); server.stop(0); }
    }
    @Test void successfulProviderUsageIsObservedWithoutExposingReasoningAndClearedBeforeNextCall() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicReference<JsonNode> received = new AtomicReference<>();
        String stream = event(Map.of("content", "answer", "reasoning_content", "PRIVATE_REASONING"), "stop")
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":10,\"total_tokens\":130,"
                + "\"private_extra\":\"not-for-observation\"}}\n\ndata: [DONE]\n\n";
        HttpServer server = server(json, received, stream);
        var gateway = gateway(json, server);
        try {
            gateway.decide(List.of(Map.of("role", "user", "content", "PRIVATE_USER_TEXT")), List.of(), ignored -> { });
            assertThat(gateway.lastObservation()).containsEntry("usage_source", "provider_response")
                    .containsEntry("usage", Map.of("prompt_tokens", 120L, "completion_tokens", 10L, "total_tokens", 130L));
            assertThat(json.writeValueAsString(gateway.lastObservation()))
                    .doesNotContain("PRIVATE_REASONING", "PRIVATE_USER_TEXT", "not-for-observation");
            assertThat(received.get().path("max_tokens").asLong()).isEqualTo(512);
            assertThat(received.get().path("stream_options").path("include_usage").asBoolean()).isTrue();
            assertThatThrownBy(() -> gateway.decide(List.of(Map.of("role", "user", "content", "x".repeat(100_000))),
                    List.of(), ignored -> { })).isInstanceOf(ModelContextBudgetException.class);
            assertThat(gateway.lastObservation()).doesNotContainKey("usage");
        } finally { gateway.close(); server.stop(0); }
    }
    @Test void incompleteResponseCannotPublishSuccessfulUsage() throws Exception {
        ObjectMapper json = new ObjectMapper();
        HttpServer server = server(json, new AtomicReference<>(), event(Map.of("content", "partial"), "length")
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}}\n\n");
        var gateway = gateway(json, server);
        try {
            assertThatThrownBy(() -> gateway.decide(List.of(), List.of(), ignored -> { })).hasMessageContaining("不完整");
            assertThat(gateway.lastObservation()).containsKey("budget").doesNotContainKey("usage");
        } finally { gateway.close(); server.stop(0); }
    }
    @Test void rejectsMalformedUsageRatherThanPublishingUntrustedCounts() throws Exception {
        ObjectMapper json = new ObjectMapper();
        HttpServer server = server(json, new AtomicReference<>(), event(Map.of("content", "answer"), "stop")
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":\"ten\"}}\n\n");
        var gateway = gateway(json, server);
        try {
            assertThatThrownBy(() -> gateway.decide(List.of(), List.of(), ignored -> { })).hasMessageContaining("usage");
            assertThat(gateway.lastObservation()).doesNotContainKey("usage");
        } finally { gateway.close(); server.stop(0); }
    }
    @Test void sharedRemainingDeadlineCancelsAStalledResponseBody() throws Exception {
        ObjectMapper json = new ObjectMapper();
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                output.write(event(Map.of("content", "waiting"), null).getBytes(StandardCharsets.UTF_8));
                output.flush();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        });
        server.start();
        var gateway = gateway(json, server);
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> gateway.decide(List.of(), List.of(), ignored -> { }, 500))
                    .hasMessageContaining("超时");
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2500L);
        } finally { release.countDown(); gateway.close(); server.stop(0); }
    }
    @Test void depletedSharedDeadlineDoesNotStartANetworkRequest() throws Exception {
        ObjectMapper json = new ObjectMapper();
        AtomicReference<JsonNode> received = new AtomicReference<>();
        HttpServer server = server(json, received, event(Map.of("content", "ok"), "stop"));
        var gateway = gateway(json, server);
        try {
            assertThatThrownBy(() -> gateway.decide(List.of(), List.of(), ignored -> { }, 0))
                    .hasMessageContaining("时间预算已耗尽");
            assertThat(received.get()).isNull();
        } finally { gateway.close(); server.stop(0); }
    }
    @Test void timeoutClassificationRecognizesNestedPublisherFailureWithoutMisclassifyingConnectionErrors() {
        assertThat(NativeFunctionCallingGateway.causedByTimeout(new IOException("publisher",
                new IOException("transport", new HttpTimeoutException("request expired"))))).isTrue();
        assertThat(NativeFunctionCallingGateway.causedByTimeout(new IOException("transport",
                new java.net.SocketTimeoutException("read expired")))).isTrue();
        assertThat(NativeFunctionCallingGateway.causedByTimeout(new IOException("connection reset"))).isFalse();
        assertThat(NativeFunctionCallingGateway.causedByTimeout(new java.net.ConnectException("connection refused"))).isFalse();
    }
    private AgentModelReply decide(String stream) throws Exception {
        ObjectMapper json = new ObjectMapper();
        HttpServer server = server(json, new AtomicReference<>(), stream);
        var gateway = gateway(json, server);
        try { return gateway.decide(List.of(), List.of(), ignored -> {}); }
        finally { gateway.close(); server.stop(0); }
    }
    private NativeFunctionCallingGateway gateway(ObjectMapper json, HttpServer server) {
        return new NativeFunctionCallingGateway(json, "http://127.0.0.1:" + server.getAddress().getPort(), "test", "test", false,
                budget(json, "test", 100_000));
    }
    private static ModelContextBudget budget(ObjectMapper json, String model, long window) {
        return new ModelContextBudget(json, new ModelContextBudget.Profile(model, "offline_test", window,
                512, 64, 128, 0.10, true), List.of(new ConservativeTokenCounter()));
    }
    private static Map<String, Object> tool(int index, String id, String name, String arguments) {
        return Map.of("index", index, "id", id, "function", Map.of("name", name, "arguments", arguments));
    }
    private static String event(Map<String, Object> delta, String finish) {
        Map<String, Object> choice = new LinkedHashMap<>(); choice.put("delta", delta);
        if (finish != null) choice.put("finish_reason", finish);
        return "data: " + new ObjectMapper().writeValueAsString(Map.of("choices", List.of(choice))) + "\n\n";
    }
    private HttpServer server(ObjectMapper json, AtomicReference<JsonNode> requestBody, String stream) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(json.readTree(exchange.getRequestBody()));
            byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start(); return server;
    }
}
