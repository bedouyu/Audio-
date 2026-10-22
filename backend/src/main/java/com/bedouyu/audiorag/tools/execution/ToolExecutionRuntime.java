package com.bedouyu.audiorag.tools.execution;

import com.bedouyu.audiorag.core.loop.AgentBudget;
import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 限制并发、排队容量和调用方的等待时间。cancel(true) 只是中断请求，不是杀线程。
 * 忽略中断的工具仍占用线程；它必须使用 assertActive() 才能阻止晚到写入。
 * 因此超时从不自动重放有副作用的操作，队列满时也明确拒绝而非无限排队。
 */
@Component
public class ToolExecutionRuntime implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final long timeoutMillis;
    private final ConcurrentMap<Future<?>, AgentExecutionContext.ToolScope> pending = new ConcurrentHashMap<>();

    @Autowired
    public ToolExecutionRuntime(@Value("${audiorag.agent.tool-workers:4}") int workerCount,
            @Value("${audiorag.agent.tool-queue-capacity:16}") int queueCapacity,
            @Value("${audiorag.agent.tool-timeout-ms:30000}") long timeoutMillis) {
        if (workerCount < 1 || queueCapacity < 1 || timeoutMillis < 1)
            throw new IllegalArgumentException("工具线程数、队列容量和超时必须为正数");
        this.timeoutMillis = timeoutMillis;
        AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(workerCount, workerCount, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), work -> {
                    Thread worker = new Thread(work, "agent-tool-" + sequence.incrementAndGet());
                    worker.setDaemon(true); return worker;
                }, new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
    }

    public <T> T await(AgentExecutionContext context, AgentExecutionContext.ToolScope scope, Supplier<T> work) {
        context.assertActive();
        long waitMillis = Math.min(timeoutMillis, context.budget.remainingMillis());
        Future<T> result;
        try {
            result = workers.submit(() -> context.withToolScope(scope, () -> {
                T value = work.get(); context.assertActive(); return value;
            }));
        } catch (RejectedExecutionException saturated) {
            String code = workers.isShutdown() ? "TOOL_RUNTIME_STOPPED" : "TOOL_BUSY";
            String detail = workers.isShutdown() ? "工具执行器已关闭" : "工具执行资源已满，请稍后发起新任务";
            scope.invalidate(code, detail);
            throw new ToolExecutionFailure(code, detail);
        }
        pending.put(result, scope);
        if (workers.isShutdown()) {
            scope.invalidate("TOOL_RUNTIME_STOPPED", "工具执行器已关闭"); cancel(result);
        }
        try {
            T value = result.get(waitMillis, TimeUnit.MILLISECONDS);
            context.assertActive(); return value;
        } catch (TimeoutException expired) {
            scope.invalidate("TOOL_TIMEOUT", "工具超过等待时限，晚到结果已作废");
            cancel(result);
            // 总任务已到期时终止整个运行；单工具超时仅作为明确失败观察反馈。
            context.budget.check();
            throw new ToolExecutionFailure("TOOL_TIMEOUT", "工具超过等待时限；不会自动重试，可能的副作用需人工核查");
        } catch (InterruptedException interrupted) {
            scope.invalidate("CANCELLED", "任务已取消"); cancel(result);
            context.budget.cancel("任务已取消"); Thread.currentThread().interrupt();
            throw new AgentBudget.AgentStopped("CANCELLED", "任务已取消");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new ToolExecutionFailure("TOOL_FAILED", "工具执行异常");
        } catch (CancellationException stopped) {
            scope.invalidate("TOOL_RUNTIME_STOPPED", "工具执行器已关闭，结果不再使用");
            throw new ToolExecutionFailure("TOOL_RUNTIME_STOPPED", "工具执行器已关闭，结果不再使用");
        } finally {
            pending.remove(result);
        }
    }
    private void cancel(Future<?> result) {
        result.cancel(true);
        if (result instanceof Runnable queued) workers.remove(queued);
    }
    public int queueSize() { return workers.getQueue().size(); }
    public int activeWorkers() { return workers.getActiveCount(); }
    @Override @PreDestroy public void close() {
        workers.shutdownNow();
        pending.forEach((future, scope) -> {
            scope.invalidate("TOOL_RUNTIME_STOPPED", "工具执行器已关闭，结果不再使用"); cancel(future);
        });
    }
    public static final class ToolExecutionFailure extends RuntimeException {
        private final String code;
        public ToolExecutionFailure(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
