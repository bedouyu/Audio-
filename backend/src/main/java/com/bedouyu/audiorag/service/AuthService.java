package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.common.Hashing;

import com.bedouyu.audiorag.repository.AudioRagRepository;
import com.bedouyu.audiorag.common.ApiException;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AudioRagRepository repository;

    public AuthService(AudioRagRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> register(String username, String password) {
        validate(username, password);
        try {
            Map<String, Object> user = repository.createUser(username.trim(), password);
            return authenticatedResponse(user);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "用户名已存在");
        }
    }

    public Map<String, Object> login(String username, String password) {
        validate(username, password);
        Map<String, Object> user = repository.authenticate(username.trim(), password)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "用户名或密码错误"));
        return authenticatedResponse(user);
    }

    public Map<String, Object> requireUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        String token = authorization.substring(7).trim();
        return repository.userForToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录"));
    }

    /** 注销当前令牌。不带 Authorization 或令牌已失效时同样视为成功，便于客户端重试。 */
    public Map<String, Object> logout(String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            repository.revokeToken(authorization.substring(7).trim());
        }
        return Map.of("loggedOut", true);
    }

    private Map<String, Object> authenticatedResponse(Map<String, Object> user) {
        long userId = ((Number) user.get("id")).longValue();
        return Map.of("token", repository.issueToken(userId), "user", user);
    }

    private void validate(String username, String password) {
        String normalized = username == null ? "" : username.trim();
        if (normalized.length() < 2 || normalized.length() > 40) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "用户名长度应为 2 到 40 个字符");
        }
        if (password == null || password.length() < 6 || password.length() > 100) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "密码长度应为 6 到 100 位");
        }
    }
}
