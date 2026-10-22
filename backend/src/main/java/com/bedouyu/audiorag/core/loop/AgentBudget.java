package com.bedouyu.audiorag.core.loop;
import java.time.*;
import java.util.*;

/** 根任务和所有子 Agent 共享预算，委派不会重置安全阀。 */
public class AgentBudget {
    private final Instant deadline;
    private final long deadlineNanos;
    private final int modelLimit;
    private final int toolLimit;
    private int models, tools, delegations, sequence;
    private String cancelledReason;
    private final Map<String, Integer> repeats = new HashMap<>();
    public AgentBudget() { this(24, 32, Duration.ofMinutes(10)); }
    public AgentBudget(int modelLimit, int toolLimit, Duration wallTime) {
        if (modelLimit < 1 || toolLimit < 1 || wallTime == null || wallTime.isNegative() || wallTime.isZero())
            throw new IllegalArgumentException("Agent 预算必须为正数");
        this.modelLimit = modelLimit; this.toolLimit = toolLimit;
        deadline = Instant.now().plus(wallTime);
        deadlineNanos = System.nanoTime() + wallTime.toNanos();
    }
    public synchronized void check() {
        if (cancelledReason != null) throw new AgentStopped("CANCELLED", cancelledReason);
        if (Thread.currentThread().isInterrupted()) throw new AgentStopped("CANCELLED", "任务已取消");
        if (deadlineNanos - System.nanoTime() <= 0) throw new AgentStopped("LIMIT_REACHED", "任务超过总时限");
    }
    /** 使用单调时钟给网络调用和工具等待传递同一根任务的剩余时限。 */
    public synchronized long remainingMillis() {
        check();
        long remaining = deadlineNanos - System.nanoTime();
        long whole = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining);
        return Math.max(1, whole + (remaining % 1_000_000 == 0 ? 0 : 1));
    }
    public synchronized void cancel(String reason) {
        if (cancelledReason == null) cancelledReason = reason == null ? "任务已取消" : reason;
    }
    public synchronized void model() {
        check(); if (models >= modelLimit) throw new AgentStopped("LIMIT_REACHED", "任务达到模型轮数上限");
        models++;
    }
    public synchronized void tool(String agent, String name, String args) {
        check(); if (tools >= toolLimit) throw new AgentStopped("LIMIT_REACHED", "任务达到工具调用上限");
        tools++;
        String key = agent + ":" + name + ":" + args;
        if (repeats.merge(key, 1, Integer::sum) > 2)
            throw new AgentStopped("LIMIT_REACHED", "同一 Agent 重复调用相同工具参数，循环已停止");
        if ("delegate_agent".equals(name)) {
            if (delegations >= 8) throw new AgentStopped("LIMIT_REACHED", "任务达到子 Agent 委派上限");
            delegations++;
        }
    }
    public synchronized int nextSequence() { return ++sequence; }
    public synchronized Map<String, Object> stats() {
        return Map.of("model_calls", models, "tool_calls", tools, "delegations", delegations,
                "model_limit", modelLimit, "tool_limit", toolLimit, "deadline", deadline.toString());
    }
    public static class AgentStopped extends RuntimeException {
        private final String status;
        public AgentStopped(String status, String message) { super(message); this.status = status; }
        public String status() { return status; }
    }
}
