package com.bedouyu.audiorag.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashingTest {
    @Test
    void matchesPythonSha256Format() {
        assertThat(Hashing.sha256("123456"))
                .isEqualTo("8d969eef6ecad3c29a3a629280e686cf0c3f" +
                        "5d5a86aff3ca12020c923adc6c92");
    }
}
