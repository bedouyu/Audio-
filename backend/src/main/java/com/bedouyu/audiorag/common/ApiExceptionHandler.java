package com.bedouyu.audiorag.common;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> handleApi(ApiException exception) {
        return ResponseEntity.status(exception.status()).body(Map.of("detail", exception.getMessage()));
    }

    /**
     * Agent 主动停机（预算耗尽、重复调用同一工具、上下文超限等）是可解释的结果，不是服务器故障。
     * 必须把原因告诉用户，不能退化成"服务处理失败"。
     */
    @ExceptionHandler(com.bedouyu.audiorag.core.loop.AgentBudget.AgentStopped.class)
    ResponseEntity<Map<String, String>> handleAgentStopped(com.bedouyu.audiorag.core.loop.AgentBudget.AgentStopped exception) {
        log.warn("Agent stopped ({}): {}", exception.status(), exception.getMessage());
        return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                .body(Map.of("detail", "任务已停止：" + exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("输入不符合要求");
        return ResponseEntity.unprocessableEntity().body(Map.of("detail", message));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> handleUnexpected(Exception exception) {
        // 数据库/供应商异常可能包含内部地址或配置，不把原始异常回传给客户端。
        log.error("Unexpected API error ({})", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(Map.of("detail", "服务处理失败，请查看本机后端日志"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> handleMalformedJson(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(Map.of("detail", "请求 JSON 缺少必要字段或字段类型不正确"));
    }
}
