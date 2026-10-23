package com.bedouyu.audiorag.ai;

import com.bedouyu.audiorag.model.dto.*;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Service;
import com.bedouyu.audiorag.ai.token.ConservativeTokenCounter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 流式聚合工具调用碎片；只允许完整结束的模型轮次进入工具执行阶段。 */
@Service
public class NativeFunctionCallingGateway implements AgentModelGateway {
    private final ObjectMapper json;
    private final URI endpoint;
    private final String key;
    private final String model;
    private final boolean thinking;
    private final ModelContextBudget budgets;
    /** 可选的复核专用模型；留空表示复核与主流程共用同一个模型。 */
    @Value("${audiorag.llm.review-model:}")
    private String reviewModel = "";
    private final ThreadLocal<Map<String, Object>> observation = ThreadLocal.withInitial(Map::of);
    private final Set<InputStream> activeStreams = ConcurrentHashMap.newKeySet();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(NativeFunctionCallingGateway.class);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "agent-model-timeout"); thread.setDaemon(true); return thread;
    });

    @Autowired
    public NativeFunctionCallingGateway(ObjectMapper json,
            @Value("${audiorag.llm.base-url}") String baseUrl,
            @Value("${audiorag.llm.api-key}") String key,
            @Value("${audiorag.llm.model}") String model,
            @Value("${audiorag.agent.thinking-enabled:false}") boolean thinking,
            ModelContextBudget budgets) {
        this.json = json; this.key = key; this.model = model; this.thinking = thinking;
        this.budgets = budgets;
        endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
    }
    /** 保留独立嵌入入口；调用前仍需显式提供 context-window 环境配置。 */
    public NativeFunctionCallingGateway(ObjectMapper json, String baseUrl, String key, String model, boolean thinking) {
        this(json, baseUrl, key, model, thinking, new ModelContextBudget(json,
                new StandardEnvironment(), List.of(new ConservativeTokenCounter())));
    }
    public boolean configured() { return key != null && !key.isBlank(); }

    @Override public Map<String, Object> budgetSnapshot(List<Map<String, Object>> messages,
                                                       List<FunctionDefinition> tools) {
        return budgets.inspect(model, requestBody(messages, tools)).data();
    }
    @Override public Map<String, Object> lastObservation() { return observation.get(); }

    private Map<String, Object> requestBody(List<Map<String, Object>> messages, List<FunctionDefinition> tools) {
        return requestBody(messages, tools, model);
    }

    private Map<String, Object> requestBody(List<Map<String, Object>> messages, List<FunctionDefinition> tools,
                                            String modelName) {
        ModelContextBudget.Profile profile = budgets.profile(modelName);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName); body.put("messages", messages); body.put("stream", true);
        body.put("max_tokens", profile.outputReserve());
        if (profile.streamUsageEnabled()) body.put("stream_options", Map.of("include_usage", true));
        if (!tools.isEmpty()) {
            body.put("tools", tools.stream().map(FunctionDefinition::wire).toList());
            body.put("tool_choice", "auto");
        }
        if (endpoint.getHost().equals("api.deepseek.com")) {
            body.put("thinking", Map.of("type", thinking ? "enabled" : "disabled"));
        }
        return body;
    }

    @Override public AgentModelReply decide(List<Map<String, Object>> messages,
                                  List<FunctionDefinition> tools, Consumer<String> contentToken) {
        return decide(messages, tools, contentToken, 120_000);
    }
    @Override public AgentModelReply decide(List<Map<String, Object>> messages, List<FunctionDefinition> tools,
                                           Consumer<String> contentToken, long timeoutMillis) {
        return decide(messages, tools, contentToken, timeoutMillis, "default");
    }
    /** 复核等用途可以指定第二模型；未配置复核模型时仍用主模型。 */
    @Override public AgentModelReply decide(List<Map<String, Object>> messages, List<FunctionDefinition> tools,
                                           Consumer<String> contentToken, long timeoutMillis, String modelTier) {
        return decideWith(messages, tools, contentToken, timeoutMillis, modelFor(modelTier));
    }
    private String modelFor(String modelTier) {
        return "review".equals(modelTier) && reviewModel != null && !reviewModel.isBlank() ? reviewModel : model;
    }
    private AgentModelReply decideWith(List<Map<String, Object>> messages, List<FunctionDefinition> tools,
                                       Consumer<String> contentToken, long timeoutMillis, String modelName) {
        observation.remove();
        if (timeoutMillis <= 0) throw new IllegalStateException("Agent 模型调用剩余时间预算已耗尽");
        long requestTimeoutMillis = Math.min(120_000, timeoutMillis);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis);
        if (!configured()) throw new IllegalStateException("未配置 Agent 模型密钥");
        Map<String, Object> body = requestBody(messages, tools, modelName);
        var budget = budgets.inspect(modelName, body);
        observation.set(Map.of("budget", budget.data()));
        if (!budget.withinBudget()) throw new ModelContextBudgetException(budget);
        long httpRemainingNanos = deadline - System.nanoTime();
        if (httpRemainingNanos <= 0) throw new IllegalStateException("Agent 模型调用超时");
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(httpRemainingNanos))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofPublisher());
            try (var input = new CancellableBodyStream(response.body())) {
                if (response.statusCode() / 100 != 2)
                    throw new IllegalStateException("Agent 模型请求失败（HTTP " + response.statusCode() + "）");
                activeStreams.add(input);
                AtomicBoolean timedOut = new AtomicBoolean();
                // The publisher subscription can be cancelled without closing a blocked JDK InputStream.
                long streamRemainingNanos = deadline - System.nanoTime();
                if (streamRemainingNanos <= 0) {
                    activeStreams.remove(input);
                    throw new IllegalStateException("Agent 模型调用超时");
                }
                var timer = timers.schedule(() -> { timedOut.set(true); closeStream(input); },
                        streamRemainingNanos, TimeUnit.NANOSECONDS);
                try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    StringBuilder content = new StringBuilder();
                    StringBuilder reasoning = new StringBuilder();
                    Map<Integer, CallBuilder> calls = new TreeMap<>();
                    Map<String, Long> usage = Map.of();
                    String finish = "";
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                        if (!line.startsWith("data:")) continue;
                        String payload = line.substring(5).strip();
                        if ("[DONE]".equals(payload)) break;
                        if (payload.length() > 128_000) throw new IllegalStateException("模型事件过大");
                        JsonNode event = json.readTree(payload);
                        if (event.hasNonNull("usage")) usage = readUsage(event.path("usage"));
                        JsonNode choice = event.path("choices").path(0);
                        JsonNode delta = choice.path("delta");
                        if (!delta.path("content").isMissingNode() && !delta.path("content").isNull()
                                && !delta.path("content").isTextual())
                            throw new IllegalStateException("模型内容字段类型无效");
                        String part = delta.path("content").asText("");
                        JsonNode toolCalls = delta.path("tool_calls");
                        if (!toolCalls.isMissingNode() && !toolCalls.isNull() && !toolCalls.isArray())
                            throw new IllegalStateException("工具调用字段类型无效");
                        if (!finish.isEmpty() && (!part.isEmpty() || !toolCalls.isEmpty()
                                || !delta.path("reasoning_content").asText("").isEmpty()))
                            throw new IllegalStateException("模型结束后仍返回输出碎片");
                        if (!part.isEmpty()) { content.append(part); contentToken.accept(part); }
                        reasoning.append(delta.path("reasoning_content").asText(""));
                        for (JsonNode item : toolCalls) {
                            JsonNode position = item.path("index");
                            if (!position.isIntegralNumber() || !position.canConvertToInt())
                                throw new IllegalStateException("工具调用序号无效");
                            int index = position.asInt();
                            if (index < 0 || index > 7) throw new IllegalStateException("工具调用序号无效");
                            if (item.hasNonNull("type") && !"function".equals(item.path("type").asText()))
                                throw new IllegalStateException("工具调用类型无效");
                            var call = calls.computeIfAbsent(index, ignored -> new CallBuilder());
                            call.accept(item);
                        }
                        if (content.length() > 32_000 || reasoning.length() > 32_000)
                            throw new IllegalStateException("模型轮次超过内容预算");
                        String reason = choice.path("finish_reason").asText("");
                        if (!reason.isBlank()) {
                            if (!finish.isEmpty() && !finish.equals(reason))
                                throw new IllegalStateException("模型返回不一致的结束原因");
                            finish = reason;
                        }
                    }
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (timedOut.get()) throw new IllegalStateException("Agent 模型调用超时");
                    if (!Set.of("stop", "tool_calls").contains(finish))
                        throw new IllegalStateException("模型输出不完整：" + (finish.isBlank() ? "流中断" : finish));
                    List<FunctionCall> parsed = calls.values().stream().map(CallBuilder::build).toList();
                    if (parsed.isEmpty() && !"stop".equals(finish))
                        throw new IllegalStateException("模型声明工具调用结束，但没有返回工具");
                    if (!parsed.isEmpty() && !"tool_calls".equals(finish))
                        throw new IllegalStateException("模型结束原因与工具调用不一致");
                    if (parsed.stream().map(FunctionCall::id).distinct().count() != parsed.size())
                        throw new IllegalStateException("模型返回重复工具调用编号");
                    Map<String, Object> completeObservation = new LinkedHashMap<>();
                    completeObservation.put("budget", budget.data());
                    if (!usage.isEmpty()) {
                        completeObservation.put("usage", usage);
                        completeObservation.put("usage_source", "provider_response");
                        completeObservation.put("usage_applies_to", "completed_request_only");
                    }
                    observation.set(Collections.unmodifiableMap(completeObservation));
                    return new AgentModelReply(content.toString(), reasoning.toString(), parsed, finish);
                } finally {
                    timer.cancel(false); activeStreams.remove(input);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Agent 模型调用已取消", exception);
        } catch (IOException exception) {
            if (Thread.currentThread().isInterrupted())
                throw new IllegalStateException("Agent 模型调用已取消", exception);
            // HttpClient publisher 可以把网络超时包装成普通 IOException，定时关闭也会表现为流异常。
            // 取消优先于超时；没有超时原因且截止时间尚未到的异常仍归因于连接/输出流失败。
            if (deadline - System.nanoTime() <= 0 || causedByTimeout(exception))
                throw new IllegalStateException("Agent 模型调用超时", exception);
            // 把底层原因写进消息并落日志：只留一句"连接失败"会让事后无法判断是断流、重置还是 TLS/代理问题。
            String cause = exception.getClass().getSimpleName()
                    + (exception.getMessage() == null ? "" : "：" + exception.getMessage());
            LOGGER.warn("模型流中断（{}）：{}", modelName, cause);
            throw new IllegalStateException("Agent 模型连接或输出流失败（" + clip(cause, 200) + "）", exception);
        }
    }

    private static String clip(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    static boolean causedByTimeout(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) return true;
        }
        return false;
    }
    /** 只读取白名单数值字段；usage 不可反推下次请求精确预算，更不能包含响应原文。 */
    private Map<String, Long> readUsage(JsonNode usage) {
        if (!usage.isObject()) throw new IllegalStateException("模型 usage 字段类型无效");
        Map<String, Long> result = new LinkedHashMap<>();
        for (String name : List.of("prompt_tokens", "completion_tokens", "total_tokens")) {
            JsonNode field = usage.path(name);
            if (field.isMissingNode() || field.isNull()) continue;
            if (!field.isIntegralNumber() || !field.canConvertToLong() || field.asLong() < 0)
                throw new IllegalStateException("模型 usage 计数字段无效");
            result.put(name, field.asLong());
        }
        return Collections.unmodifiableMap(result);
    }
    /** Request one batch at a time; waiting for the next batch is interruptible. */
    private static final class CancellableBodyStream extends InputStream
            implements Flow.Subscriber<List<ByteBuffer>> {
        private record Batch(List<ByteBuffer> buffers, Throwable failure, boolean end) { }
        private static final Batch END = new Batch(List.of(), null, true);
        // At most one requested data batch and its terminal notification can be queued.
        private final BlockingQueue<Batch> pending = new ArrayBlockingQueue<>(2);
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile Flow.Subscription subscription;
        private Iterator<ByteBuffer> buffers = Collections.emptyIterator();
        private ByteBuffer current;
        private boolean consumedBatch;

        CancellableBodyStream(Flow.Publisher<List<ByteBuffer>> publisher) { publisher.subscribe(this); }

        @Override public void onSubscribe(Flow.Subscription next) {
            if (subscription != null) { next.cancel(); return; }
            subscription = next;
            if (closed.get()) next.cancel(); else next.request(1);
        }
        @Override public void onNext(List<ByteBuffer> next) {
            if (closed.get()) return;
            if (!pending.offer(new Batch(List.copyOf(next), null, false))) {
                subscription.cancel();
                pending.clear();
                pending.offer(new Batch(List.of(), new IOException("模型响应违反流式背压协议"), true));
            }
        }
        @Override public void onError(Throwable failure) { pending.offer(new Batch(List.of(), failure, true)); }
        @Override public void onComplete() { pending.offer(END); }

        @Override public int read() throws IOException {
            byte[] value = new byte[1];
            return read(value, 0, 1) < 0 ? -1 : value[0] & 0xff;
        }
        @Override public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) return 0;
            if (!nextBuffer()) return -1;
            int count = Math.min(length, current.remaining());
            current.get(target, offset, count);
            return count;
        }
        private boolean nextBuffer() throws IOException {
            while (!closed.get()) {
                if (Thread.currentThread().isInterrupted()) {
                    close(); throw new InterruptedIOException("Agent 模型调用已取消");
                }
                if (current != null && current.hasRemaining()) return true;
                if (buffers.hasNext()) { current = buffers.next(); continue; }
                if (consumedBatch) { consumedBatch = false; subscription.request(1); }
                final Batch batch;
                try { batch = pending.take(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); close();
                    var cancelled = new InterruptedIOException("Agent 模型调用已取消");
                    cancelled.initCause(interrupted); throw cancelled;
                }
                if (batch.failure() != null) throw new IOException("Agent 模型输出流失败", batch.failure());
                if (batch.end()) return false;
                buffers = batch.buffers().iterator(); consumedBatch = true;
            }
            return false;
        }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            Flow.Subscription active = subscription;
            if (active != null) active.cancel();
            pending.clear(); pending.offer(END);
        }
    }
    private static class CallBuilder {
        final StringBuilder id = new StringBuilder(), name = new StringBuilder(), arguments = new StringBuilder();
        void accept(JsonNode item) {
            String identifier = text(item.path("id"));
            if (!identifier.isEmpty()) {
                // IDs identify a call; repeated metadata is not a new string delta.
                if (!id.isEmpty() && !id.toString().equals(identifier))
                    throw new IllegalStateException("同一工具序号返回不一致的调用编号");
                if (id.isEmpty()) id.append(identifier);
            }
            String function = text(item.path("function").path("name"));
            // Providers may send a split name or repeat the entire known name. Arguments always append.
            if (!function.isEmpty() && !name.toString().equals(function)) name.append(function);
            arguments.append(text(item.path("function").path("arguments")));
            if (id.length() > 128 || name.length() > 64)
                throw new IllegalStateException("工具调用编号或名称过长");
            if (arguments.length() > 32_000) throw new IllegalStateException("工具参数过长");
        }
        private static String text(JsonNode node) {
            if (node.isMissingNode() || node.isNull()) return "";
            if (!node.isTextual()) throw new IllegalStateException("工具调用字段类型无效");
            return node.asText();
        }
        FunctionCall build() {
            if (!id.toString().matches("[A-Za-z0-9_-]{1,128}")
                    || !name.toString().matches("[a-z][a-z0-9_]{0,63}"))
                throw new IllegalStateException("工具调用编号或名称无效");
            return new FunctionCall(id.toString(), name.toString(),
                    arguments.isEmpty() ? "{}" : arguments.toString());
        }
    }
    private static void closeStream(InputStream input) {
        try { input.close(); } catch (IOException ignored) { }
    }
    @PreDestroy void close() {
        activeStreams.forEach(NativeFunctionCallingGateway::closeStream);
        timers.shutdownNow();
    }
}
