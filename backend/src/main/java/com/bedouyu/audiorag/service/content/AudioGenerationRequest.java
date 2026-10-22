package com.bedouyu.audiorag.service.content;

/** Input shared by single and continuous audio; preferences stay in the original instruction. */
public record AudioGenerationRequest(AudioIntent intent, String audience, boolean continuous, String contextScript) {
    public AudioGenerationRequest(AudioIntent intent, String audience, boolean continuous) {
        this(intent, audience, continuous, null);
    }
}
