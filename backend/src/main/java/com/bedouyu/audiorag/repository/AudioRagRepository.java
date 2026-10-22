package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.common.PasswordHasher;
import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.mapper.AudioApiMapper;
import com.bedouyu.audiorag.model.entity.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/** 数据访问门面：业务层不写 SQL，原接口格式由 mapper 保持兼容。 */
@Repository
@Transactional
public class AudioRagRepository {
    private final UserAccountRepository users;
    private final AuthTokenRepository tokens;
    private final LearningProjectRepository projects;
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final AgentEventRepository events;
    private final ProjectRecordRepository records;
    private final LearningPointRepository points;
    private final AudioApiMapper mapper;
    private final SecureRandom random = new SecureRandom();
    /** 登录令牌有效期；过期即失效，也可通过 /auth/logout 主动注销。 */
    private static final Duration TOKEN_LIFETIME = Duration.ofDays(30);

    public AudioRagRepository(UserAccountRepository users, AuthTokenRepository tokens,
            LearningProjectRepository projects, ChatSessionRepository sessions,
            ChatMessageRepository messages, AgentEventRepository events,
            ProjectRecordRepository records, LearningPointRepository points, AudioApiMapper mapper) {
        this.users = users; this.tokens = tokens; this.projects = projects; this.sessions = sessions;
        this.messages = messages; this.events = events; this.records = records;
        this.points = points; this.mapper = mapper;
    }

    public Map<String, Object> createUser(String username, String password) {
        return mapper.user(users.saveAndFlush(new UserAccount(username, PasswordHasher.hash(password))));
    }
    /** 校验通过后把历史无盐口令就地升级为 PBKDF2，存量用户无需重置密码。 */
    public Optional<Map<String, Object>> authenticate(String username, String password) {
        return users.findByUsername(username)
                .filter(user -> PasswordHasher.verify(password, user.getPasswordHash()))
                .map(user -> {
                    if (PasswordHasher.needsUpgrade(user.getPasswordHash())) {
                        user.updatePasswordHash(PasswordHasher.hash(password));
                        users.save(user);
                    }
                    return mapper.user(user);
                });
    }
    public String issueToken(long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.save(new AuthToken(Hashing.sha256(token), userId, LocalDateTime.now().plus(TOKEN_LIFETIME)));
        return token;
    }
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> userForToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        return tokens.findById(Hashing.sha256(token))
                .filter(value -> !value.isExpired(now))
                .flatMap(value -> users.findById(value.getUserId())).map(mapper::user);
    }
    /** 注销：删除该令牌；未知或已失效的令牌同样静默成功。 */
    public void revokeToken(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        tokens.findById(Hashing.sha256(token)).ifPresent(tokens::delete);
    }
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listProjects(long userId) {
        return projects.findByUserIdOrderByUpdatedAtDescIdDesc(userId).stream().map(mapper::project).toList();
    }
    public Map<String, Object> ensureProject(long userId, String topic) {
        String normalized = topic.trim().replaceAll("\\s+", " ");
        LearningProject project = projects.findByUserIdAndTopic(userId, normalized)
                .orElseGet(() -> projects.save(new LearningProject(userId, normalized, normalized)));
        project.touch();
        return mapper.project(project);
    }
    public Map<String, Object> ensureAudioProject(long userId, String instruction) {
        users.findLockedById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "用户不存在"));
        String normalized = instruction.strip().replaceAll("\\s+", " ");
        String topic = normalized.length() <= 500 ? normalized : normalized.substring(0, 420) + " · " + Hashing.sha256(normalized);
        String title = normalized.substring(0, Math.min(72, normalized.length()));
        LearningProject project = projects.findByUserIdAndTopic(userId, topic)
                .orElseGet(() -> projects.saveAndFlush(new LearningProject(userId, topic, title)));
        project.touch();
        return mapper.project(project);
    }
    @Transactional(readOnly = true)
    public Map<String, Object> project(long userId, long projectId) {
        requireProject(userId, projectId);
        return mapper.project(projects.findById(projectId).orElseThrow());
    }
    @Transactional(readOnly = true)
    public Map<String, Object> projectForKnowledge(long userId, long recordId) {
        var record = records.findById(recordId).filter(r -> "knowledge".equals(r.getRecordType()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "指定资料不存在或无访问权限"));
        return project(userId, record.getProjectId());
    }
    @Transactional(readOnly = true)
    public boolean ownsProject(long userId, long projectId) {
        return projects.existsByIdAndUserId(projectId, userId);
    }
    @Transactional(readOnly = true)
    public String projectTopic(long userId, long projectId) {
        requireProject(userId, projectId);
        return projects.findById(projectId).orElseThrow().getTopic();
    }

    public String openSession(long userId, long projectId, String requestedId) {
        requireProject(userId, projectId);
        String id = requestedId == null || requestedId.isBlank() ? UUID.randomUUID().toString() : requestedId;
        if (!id.matches("[0-9a-fA-F-]{36}")) throw new ApiException(HttpStatus.BAD_REQUEST, "会话编号无效");
        Optional<ChatSession> existing = sessions.findById(id);
        if (existing.isPresent()) { requireSession(userId, projectId, id); return id; }
        // 旧版只在项目记录中保存会话归属，按同项目记录核验后才能接续。
        if (messages.existsBySessionId(id) && !legacySessionBelongsTo(projectId, id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "对话不属于当前项目");
        }
        sessions.save(new ChatSession(id, userId, projectId, "新的对话"));
        return id;
    }
    public void requireSession(long userId, long projectId, String id) {
        ChatSession session = sessions.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "会话不存在"));
        if (session.getUserId() != userId || session.getProjectId() != projectId)
            throw new ApiException(HttpStatus.NOT_FOUND, "对话不属于当前项目");
    }
    public void requireSessionOwner(long userId, String id) {
        ChatSession session = sessions.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "会话不存在"));
        requireSession(userId, session.getProjectId(), id);
    }
    public List<Map<String, Object>> sessions(long userId, long projectId) {
        requireProject(userId, projectId);
        restoreLegacySessions(userId, projectId);
        return sessions.findByUserIdAndProjectIdOrderByUpdatedAtDesc(userId, projectId).stream()
                .map(mapper::session).toList();
    }
    @Transactional(readOnly = true)
    public List<Map<String, Object>> sessionMessages(long userId, long projectId, String id) {
        requireSession(userId, projectId, id);
        return messages.findBySessionIdOrderByIdAsc(id).stream()
                .map(value -> Map.<String, Object>of("id", value.getId(), "role", value.getRole(),
                        "content", value.getContent(), "turn_id", value.getTurnId() == null ? "" : value.getTurnId())).toList();
    }
    @Transactional(readOnly = true)
    public List<ChatMessage> recentMessages(String id) {
        List<ChatMessage> newest = new ArrayList<>(messages.findTop12BySessionIdOrderByIdDesc(id));
        Collections.reverse(newest);
        return List.copyOf(newest);
    }
    /** 更深的有界扫描用于memory策略；与旧Top12入口分开，且先核验会话归属。 */
    @Transactional(readOnly = true)
    public List<ChatMessage> memoryMessages(long userId, long projectId, String sessionId, int maximum) {
        requireProject(userId, projectId);
        requireSession(userId, projectId, sessionId);
        if (maximum < 2 || maximum > 400) throw new IllegalArgumentException("Memory scan limit is 2..400 messages");
        var newest = new ArrayList<>(messages.findBySessionIdOrderByIdDesc(sessionId,
                org.springframework.data.domain.PageRequest.of(0, maximum)));
        Collections.reverse(newest);
        return List.copyOf(newest);
    }
    /** IDs永远和session一起过滤，不能通过猜消息ID读取另一个会话。 */
    @Transactional(readOnly = true)
    public List<ChatMessage> memorySources(long userId, long projectId, String sessionId, List<Long> ids) {
        requireProject(userId, projectId);
        requireSession(userId, projectId, sessionId);
        if (ids.size() > 128) throw new IllegalArgumentException("Too many memory source IDs");
        return ids.isEmpty() ? List.of() : messages.findBySessionIdAndIdInOrderByIdAsc(sessionId, ids);
    }
    public void appendMessage(String sessionId, String role, String content) {
        messages.save(new ChatMessage(sessionId, role, content));
        sessions.findById(sessionId).ifPresent(ChatSession::touch);
    }
    public void appendTurn(String sessionId, String question, String answer) {
        String turnId = UUID.randomUUID().toString();
        messages.save(new ChatMessage(sessionId, "user", question, turnId));
        messages.save(new ChatMessage(sessionId, "assistant", answer, turnId));
        sessions.findById(sessionId).ifPresent(session -> session.recordTurn(question));
    }
    public void appendEvent(String sessionId, int step, String type, String name, Map<String, Object> payload) {
        events.save(new AgentEvent(sessionId, step, type, name, mapper.json(payload)));
    }
    @Transactional(readOnly = true)
    public List<Map<String, Object>> events(String sessionId) {
        return events.findBySessionIdOrderByIdAsc(sessionId).stream().map(mapper::event).toList();
    }
    public long addProjectRecord(long projectId, String type, String title, String content, Map<String, Object> metadata) {
        ProjectRecord result = records.save(new ProjectRecord(projectId, type, title, content, mapper.json(metadata)));
        projects.findById(projectId).ifPresent(LearningProject::touch);
        return result.getId();
    }
    @Transactional(readOnly = true)
    public List<Map<String, Object>> knowledgeRecords(long userId, long projectId) {
        if (!ownsProject(userId, projectId)) return List.of();
        return records.findByProjectIdAndRecordTypeOrderByIdDesc(projectId, "knowledge").stream()
                .map(mapper::record).toList();
    }
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> knowledgeRecord(long userId, long projectId, long recordId) {
        if (!ownsProject(userId, projectId)) return Optional.empty();
        return records.findByIdAndProjectIdAndRecordType(recordId, projectId, "knowledge").map(mapper::record);
    }
    public void deleteProjectRecord(long recordId) { records.deleteById(recordId); }
    public void deleteSessions(List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        events.deleteBySessionIdIn(ids);
        messages.deleteBySessionIdIn(ids);
        sessions.deleteAllById(ids);
    }
    public void deleteProject(long userId, long projectId) {
        requireProject(userId, projectId);
        List<String> ids = sessions.findByUserIdAndProjectIdOrderByUpdatedAtDesc(userId, projectId).stream()
                .map(ChatSession::getId).toList();
        deleteSessions(ids);
        points.deleteByProjectId(projectId);
        records.deleteByProjectId(projectId);
        sessions.deleteByProjectId(projectId);
        projects.deleteById(projectId);
    }
    @Transactional(readOnly = true)
    public List<Map<String, Object>> projectRecords(long userId, long projectId) {
        if (!ownsProject(userId, projectId)) return List.of();
        return records.findByProjectIdOrderByIdDesc(projectId).stream().map(mapper::record).toList();
    }
    private void requireProject(long userId, long projectId) {
        if (!ownsProject(userId, projectId)) throw new ApiException(HttpStatus.NOT_FOUND, "项目不存在或无访问权限");
    }
    /** 旧版归属在项目记录中；补充会话表即可恢复原消息，不需要复制或改写历史。 */
    @SuppressWarnings("unchecked")
    private void restoreLegacySessions(long userId, long projectId) {
        Set<String> ids = new LinkedHashSet<>();
        for (ProjectRecord record : records.findByProjectIdAndRecordTypeOrderByIdDesc(projectId, "chat")) {
            var metadata = (Map<String, Object>) mapper.record(record).get("metadata");
            Object value = metadata.get("session_id");
            if (value instanceof String id && id.matches("[0-9a-fA-F-]{36}")) ids.add(id);
        }
        Set<String> existing = new HashSet<>();
        sessions.findAllById(ids).forEach(session -> existing.add(session.getId()));
        for (String id : ids) if (!existing.contains(id)) {
            sessions.save(new ChatSession(id, userId, projectId, "历史对话 · " + id.substring(0, 8)));
        }
    }
    @SuppressWarnings("unchecked")
    private boolean legacySessionBelongsTo(long projectId, String id) {
        return records.findByProjectIdAndRecordTypeOrderByIdDesc(projectId, "chat").stream()
                .map(mapper::record).anyMatch(record -> id.equals(((Map<String, Object>) record.get("metadata")).get("session_id")));
    }
}
