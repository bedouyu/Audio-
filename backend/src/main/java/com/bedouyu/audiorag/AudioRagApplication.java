package com.bedouyu.audiorag;

import com.bedouyu.audiorag.config.LoopbackBypassProxySelector;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AudioRagApplication {
    public static void main(String[] args) {
        LoopbackBypassProxySelector.installIfEnabled();
        SpringApplication.run(AudioRagApplication.class, args);
    }
}
