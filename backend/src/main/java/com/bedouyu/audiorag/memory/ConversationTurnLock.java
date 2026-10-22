package com.bedouyu.audiorag.memory;

import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.bedouyu.audiorag.common.ApiException;
import org.springframework.http.HttpStatus;

/** 本机单进程的会话回合保护：原生/基础入口共用，避免同会话读旧快照并交错写入。不是分布式锁。 */
@Component
public class ConversationTurnLock {
    private final Set<String> active = ConcurrentHashMap.newKeySet();
    public void enter(String session) {
        if (!active.add(session)) throw new ApiException(HttpStatus.CONFLICT, "同一会话已有任务运行，请等待结束");
    }
    public void leave(String session) { active.remove(session); }
}
