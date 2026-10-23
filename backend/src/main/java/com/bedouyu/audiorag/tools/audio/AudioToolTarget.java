package com.bedouyu.audiorag.tools.audio;

import com.bedouyu.audiorag.core.loop.AgentExecutionContext;
import com.bedouyu.audiorag.service.ContinuousAudioStore;
import org.springframework.stereotype.Component;

/**
 * 定位 agent 要操作的音频队列：优先调用方给的 queue_id，其次本项目最新一条，
 * 最后退回该用户最近一条。项目维度查不到时不直接失败——聊天上下文里的 project_id
 * 偶尔对不上项目，这里退一步仍能继续，真实错误会在最终异常里带出两边的标识。
 */
@Component
public class AudioToolTarget {
    private final ContinuousAudioStore store;

    public AudioToolTarget(ContinuousAudioStore store) { this.store = store; }

    public String resolve(AgentExecutionContext context, String explicit) {
        // 模型常把产物 id 或占位字符串当成 queue_id 传来；校验不过就退回"最近一条音频"，
        // 不要因为一个错误的可选参数让整次改稿失败。
        if (explicit != null && !explicit.isBlank()) {
            String candidate = explicit.strip();
            try {
                store.requireOwned(context.userId, candidate);
                return candidate;
            } catch (RuntimeException invalidId) {
                // 继续按最近一条音频兜底
            }
        }
        try {
            var latest = store.latestQueue(context.userId, context.projectId);
            if (latest.isPresent()) return latest.get();
        } catch (RuntimeException perProjectFailure) {
            // 继续尝试用户维度
        }
        return store.latestQueueForUser(context.userId).orElseThrow(() -> new IllegalStateException(
                "没有找到可操作的音频（project_id=" + context.projectId + "，user_id=" + context.userId + "）"));
    }
}
