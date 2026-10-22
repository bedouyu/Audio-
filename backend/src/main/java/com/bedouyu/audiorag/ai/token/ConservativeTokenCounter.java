package com.bedouyu.audiorag.ai.token;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;

/**
 * 未安装模型 tokenizer 时按 UTF-8 字节估计，故意不套用英文“四字符一个 token”。
 * 这是一种保守预算启发式，不保证等于、也不声称严格上界于任何未知供应商 tokenizer。
 */
@Component
public final class ConservativeTokenCounter implements TokenCounter {
    @Override public boolean supports(String model) { return true; }
    @Override public int priority() { return Integer.MIN_VALUE; }
    @Override public Count count(String model, String text) {
        return new Count(text.getBytes(StandardCharsets.UTF_8).length,
                "utf8_byte_estimate", true);
    }
}
