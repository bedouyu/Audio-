package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.agent.audio.AudioAgent.ChatCommand;
import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.memory.ConversationTurnLock;
import com.bedouyu.audiorag.repository.AudioRagRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** 先按用户/项目/request锁和数据库幂等记录复用，再创建会话；模型不会因网络重发被再次调用。 */
@Service
public class AgentRequestCoordinator {
    private final AudioRagRepository repository;
    private final AgentRunStore runs;
    private final ConversationTurnLock locks;
    private final ObjectMapper json;
    public AgentRequestCoordinator(AudioRagRepository repository, AgentRunStore runs, ConversationTurnLock locks, ObjectMapper json) {
        this.repository = repository; this.runs = runs; this.locks = locks; this.json = json;
    }
    public Reservation begin(long user, ChatCommand command) {
        String request = canonical(command.requestId());
        String requestLock = request == null ? null : "request:" + user + ":" + command.projectId() + ":" + request;
        String fingerprint = request == null ? null : fingerprint(command);
        if (requestLock != null) locks.enter(requestLock);
        String session = null;
        boolean sessionLocked = false;
        try {
            if (request != null) {
                var cached = runs.replay(user, command.projectId(), request, fingerprint);
                if (cached.isPresent()) return new Reservation(null, null, cached.get(), requestLock, false);
            }
            session = repository.openSession(user, command.projectId(), command.sessionId());
            locks.enter(session); sessionLocked = true;
            String run = request == null ? runs.start(user, command.projectId(), session, command.message())
                    : runs.start(user, command.projectId(), session, command.message(), request, fingerprint);
            return new Reservation(session, run, null, requestLock, true);
        } catch (RuntimeException error) {
            if (sessionLocked) locks.leave(session);
            if (requestLock != null) locks.leave(requestLock);
            throw error;
        }
    }
    private String fingerprint(ChatCommand command) {
        Map<String, Object> input = new TreeMap<>();
        input.put("message", command.message()); input.put("requested_session_id", command.sessionId());
        input.put("memory_strategy", command.memoryStrategy());
        // null允许开放式推断，显式[]代表用户声明；二者不能共用幂等结果。
        input.put("required_artifacts_supplied", command.requiredArtifacts() != null);
        input.put("required_artifacts", command.requiredArtifacts() == null ? List.of() : command.requiredArtifacts().stream().sorted().toList());
        return Hashing.sha256(json.writeValueAsString(input));
    }
    private static String canonical(String request) {
        if (request == null || request.isBlank()) return null;
        if (request.length() != 36 || !UUID.fromString(request).toString().equalsIgnoreCase(request))
            throw new IllegalArgumentException("request_id必须为UUID");
        return request.toLowerCase(Locale.ROOT);
    }
    public final class Reservation implements AutoCloseable {
        private final String sessionId, runId, requestLock;
        private final Map<String, Object> replay;
        private final boolean sessionLocked;
        private boolean closed;
        private Reservation(String session, String run, Map<String, Object> replay, String requestLock, boolean sessionLocked) {
            this.sessionId = session; this.runId = run; this.replay = replay; this.requestLock = requestLock; this.sessionLocked = sessionLocked;
        }
        public String sessionId() { return sessionId; }
        public String runId() { return runId; }
        public Map<String, Object> replay() { return replay; }
        public void close() {
            if (closed) return;
            closed = true;
            if (sessionLocked) locks.leave(sessionId);
            if (requestLock != null) locks.leave(requestLock);
        }
    }
}
