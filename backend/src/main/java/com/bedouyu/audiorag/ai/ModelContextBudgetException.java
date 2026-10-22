package com.bedouyu.audiorag.ai;

import java.util.Map;

/** 可压缩的发送前失败；没有访问网络，不计为一次模型执行。 */
public final class ModelContextBudgetException extends IllegalStateException {
    private final ModelContextBudget.Snapshot snapshot;
    public ModelContextBudgetException(ModelContextBudget.Snapshot snapshot) {
        super("完整模型请求超出上下文预算：" + snapshot.totalReserved() + " > " + snapshot.contextWindow()
                + "（" + snapshot.countingMethod() + "，估算；可压缩历史和工具观察后重试）");
        this.snapshot = snapshot;
    }
    public ModelContextBudget.Snapshot snapshot() { return snapshot; }
    public Map<String, Object> metadata() { return snapshot.data(); }
}
