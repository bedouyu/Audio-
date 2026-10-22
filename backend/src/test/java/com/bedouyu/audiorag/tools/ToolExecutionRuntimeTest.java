package com.bedouyu.audiorag.tools;

import com.bedouyu.audiorag.core.loop.*;
import com.bedouyu.audiorag.tools.execution.ToolExecutionRuntime;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ToolExecutionRuntimeTest {
    @Test void sharedBudgetExposesOneDeadlineAndCancellationToChildren() {
        var root = context(new ArrayList<>(), Duration.ofSeconds(2));
        var child = root.child("writer", Set.of());
        assertThat(child.budget).isSameAs(root.budget);
        assertThat(child.budget.remainingMillis()).isBetween(1L, 2000L);
        root.budget.cancel("用户取消");
        assertThatThrownBy(child::assertActive).isInstanceOfSatisfying(AgentBudget.AgentStopped.class,
                stopped -> assertThat(stopped.status()).isEqualTo("CANCELLED"));
    }

    @Test void expiredRootDeadlineStopsWaitingInsteadOfWaitingForToolTimeout() throws Exception {
        var context = context(new ArrayList<>(), Duration.ofMillis(80));
        var scope = context.openToolScope("wire-call");
        try (var runtime = new ToolExecutionRuntime(1, 1, 2000)) {
            long start = System.nanoTime();
            assertThatThrownBy(() -> runtime.await(context, scope, () -> {
                try { new CountDownLatch(1).await(); } catch (InterruptedException ignored) { }
                return "late";
            })).isInstanceOf(AgentBudget.AgentStopped.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(1500);
        }
    }

    @Test void interruptCancelsSharedRunAndPreservesCallerInterruptFlag() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        var context = context(new ArrayList<>(), Duration.ofSeconds(5));
        try (var runtime = new ToolExecutionRuntime(1, 1, 4000)) {
            Thread caller = new Thread(() -> {
                try {
                    runtime.await(context, context.openToolScope("call"), () -> {
                        entered.countDown();
                        try { new CountDownLatch(1).await(); } catch (InterruptedException ignored) { }
                        return null;
                    });
                } catch (Throwable stopped) { failure.set(stopped); interrupted.set(Thread.currentThread().isInterrupted()); }
            });
            caller.start(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); caller.interrupt(); caller.join(2000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOfSatisfying(AgentBudget.AgentStopped.class,
                    stop -> assertThat(stop.status()).isEqualTo("CANCELLED"));
            assertThat(interrupted.get()).isTrue();
            assertThatThrownBy(context::assertActive).isInstanceOf(AgentBudget.AgentStopped.class);
        }
    }

    @Test void timeoutInvalidatesLateWorkerAndItsExplicitChildWithoutPublishingSuccess() throws Exception {
        CountDownLatch release = new CountDownLatch(1), workerExited = new CountDownLatch(1);
        List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
        var context = context(events, Duration.ofSeconds(5));
        AtomicReference<AgentExecutionContext> child = new AtomicReference<>();
        AtomicReference<Throwable> lateFailure = new AtomicReference<>();
        var scope = context.openToolScope("wire-call");
        try (var runtime = new ToolExecutionRuntime(1, 1, 200)) {
            assertThatThrownBy(() -> runtime.await(context, scope, () -> {
                child.set(context.child("writer", Set.of()));
                // 模拟不合作的依赖：忽略中断，但拿到结果后仍须通过作用域检查。
                awaitIgnoringInterrupt(release);
                try { child.get().emit("artifact", "", "COMPLETED", "不应发出", Map.of()); }
                catch (Throwable expired) { lateFailure.set(expired); }
                finally { workerExited.countDown(); }
                return "late";
            })).isInstanceOfSatisfying(ToolExecutionRuntime.ToolExecutionFailure.class,
                    failure -> assertThat(failure.code()).isEqualTo("TOOL_TIMEOUT"));
            release.countDown(); assertThat(workerExited.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(lateFailure.get()).isInstanceOf(AgentBudget.AgentStopped.class);
            assertThatThrownBy(child.get()::assertActive).hasMessageContaining("晚到结果");
            assertThat(events).isEmpty();
            assertThatCode(context::assertActive).doesNotThrowAnyException();
        } finally { release.countDown(); }
    }

    @Test void fullQueueRejectsImmediatelyAndDoesNotCreateUnlimitedWorkers() throws Exception {
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();
        try (var runtime = new ToolExecutionRuntime(1, 1, 4000)) {
            Thread first = waitingCaller(runtime, release, entered, unexpected);
            Thread second = waitingCaller(runtime, release, null, unexpected);
            first.start(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); second.start();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (runtime.queueSize() != 1 && System.nanoTime() < until) Thread.sleep(5);
            assertThat(runtime.queueSize()).isEqualTo(1); assertThat(runtime.activeWorkers()).isEqualTo(1);
            var context = context(new ArrayList<>(), Duration.ofSeconds(5));
            assertThatThrownBy(() -> runtime.await(context, context.openToolScope("third"), () -> "ignored"))
                    .isInstanceOfSatisfying(ToolExecutionRuntime.ToolExecutionFailure.class,
                            failure -> assertThat(failure.code()).isEqualTo("TOOL_BUSY"));
            release.countDown(); first.join(2000); second.join(2000);
            assertThat(first.isAlive()).isFalse(); assertThat(second.isAlive()).isFalse(); assertThat(unexpected).isEmpty();
        } finally { release.countDown(); }
    }

    @Test void runtimePropagatesToolFailuresWithoutCallingTheSupplierAgain() {
        var context = context(new ArrayList<>(), Duration.ofSeconds(5));
        try (var runtime = new ToolExecutionRuntime(1, 1, 1000)) {
            assertThatThrownBy(() -> runtime.await(context, context.openToolScope("call"),
                    () -> { throw new IllegalArgumentException("工具错误"); })).hasMessage("工具错误");
        }
    }

    @Test void closingRuntimeUnblocksWaitingCallerEvenForAnUncooperativeTool() throws Exception {
        var runtime = new ToolExecutionRuntime(1, 1, 4000);
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            var context = context(new ArrayList<>(), Duration.ofSeconds(5));
            try { runtime.await(context, context.openToolScope("call"), () -> {
                entered.countDown(); awaitIgnoringInterrupt(release); return "late";
            }); } catch (Throwable stopped) { failure.set(stopped); }
        });
        try {
            caller.start(); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue(); runtime.close(); caller.join(2000);
            assertThat(caller.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOfSatisfying(ToolExecutionRuntime.ToolExecutionFailure.class,
                    stopped -> assertThat(stopped.code()).isEqualTo("TOOL_RUNTIME_STOPPED"));
        } finally { release.countDown(); runtime.close(); caller.join(2000); }
    }

    @Test void closedRuntimeRejectsNewWorkWithItsOwnErrorCode() {
        var runtime = new ToolExecutionRuntime(1, 1, 1000); runtime.close();
        var context = context(new ArrayList<>(), Duration.ofSeconds(5));
        assertThatThrownBy(() -> runtime.await(context, context.openToolScope("call"), () -> "never"))
                .isInstanceOfSatisfying(ToolExecutionRuntime.ToolExecutionFailure.class,
                        stopped -> assertThat(stopped.code()).isEqualTo("TOOL_RUNTIME_STOPPED"));
    }

    private static Thread waitingCaller(ToolExecutionRuntime runtime, CountDownLatch release,
                                       CountDownLatch entered, List<Throwable> failures) {
        return new Thread(() -> {
            var context = context(new ArrayList<>(), Duration.ofSeconds(5));
            try { runtime.await(context, context.openToolScope("call"), () -> {
                if (entered != null) entered.countDown(); awaitIgnoringInterrupt(release); return "OK";
            }); } catch (Throwable failure) { failures.add(failure); }
        });
    }
    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        while (latch.getCount() > 0) try { latch.await(); } catch (InterruptedException ignored) { }
    }
    private static AgentExecutionContext context(List<Map<String, Object>> events, Duration duration) {
        return new AgentExecutionContext("run", "session", "supervisor", 1, 7,
                new AgentBudget(24, 32, duration), new EvidenceLedger(), null, null, events::add);
    }
}
