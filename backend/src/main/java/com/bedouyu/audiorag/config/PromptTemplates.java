package com.bedouyu.audiorag.config;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

@Component
public class PromptTemplates {
    public String read(String name) {
        try (var input = new ClassPathResource("prompts/audio/" + name + ".txt").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) { throw new IllegalStateException("提示词不存在：" + name, exception); }
    }
}
