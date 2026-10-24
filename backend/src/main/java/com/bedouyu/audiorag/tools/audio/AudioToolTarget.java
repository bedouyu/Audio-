package com.bedouyu.audiorag.tools.audio;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.service.ContinuousAudioStore;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 音频操作绑定用户当前选中的队列；无选中值时才使用明确编号或本项目最近一条。
 * 编号无效、跨项目或与已选队列不一致时明确失败，不能悄悄换成另一条音频。
 */
@Component
public class AudioToolTarget {
    private final ContinuousAudioStore store;

    public AudioToolTarget(ContinuousAudioStore store) { this.store = store; }

    /** The UI reads full diffs from the queue; avoid duplicating whole scripts in the model's context. */
    static Map<String, Object> revisionSummary(Map<String, Object> snapshot) {
        var result = new LinkedHashMap<String, Object>();
        if (snapshot.get("script_revision") instanceof Map<?, ?> revision) {
            for (String key : new String[]{"id", "queue_id", "kind", "status", "segment_number"})
                result.put(key, revision.get(key));
        }
        return result;
    }

    public String resolve(AgentExecutionContext context, String explicit) {
        context.assertActive();
        var root = context.rootContext();
        synchronized (root) {
            String selected = root.audioQueueId == null ? "" : root.audioQueueId.strip();
            String requested = explicit == null ? "" : explicit.strip();
            if (!selected.isBlank() && !requested.isBlank() && !selected.equalsIgnoreCase(requested))
                throw new IllegalArgumentException("工具指定的音频与当前选中音频不一致，请先切换到要修改的音频");
            String candidate = !selected.isBlank() ? selected : requested;
            if (candidate.isBlank()) {
                candidate = store.latestQueue(context.userId, context.projectId).orElseThrow(() ->
                        new IllegalStateException("当前内容空间没有可操作的音频，请先打开或生成音频"));
            }
            // 显式错误及数据库故障向调用方传播，不允许回退到本项目/用户的其他队列。
            store.requireOwnedInProject(context.userId, context.projectId, candidate);
            context.assertActive();
            root.audioQueueId = candidate;
            context.audioQueueId = candidate;
            return candidate;
        }
    }
}
