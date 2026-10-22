package com.bedouyu.audiorag.ai.token;

/**
 * 生成模型计数适配点。实现必须声明支持的模型，不可拿 embedding tokenizer 冒充生成 tokenizer。
 * 即使字符串本身使用精确 tokenizer，JSON 请求仍不是供应商的内部 chat template。
 */
public interface TokenCounter {
    boolean supports(String model);
    default int priority() { return 0; }
    Count count(String model, String text);

    record Count(long tokens, String method, boolean estimated) {
        public Count {
            if (tokens < 0 || method == null || method.isBlank())
                throw new IllegalArgumentException("TokenCounter 返回无效计数");
        }
    }
}
