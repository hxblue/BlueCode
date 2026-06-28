package com.bluecode.llm;

public record SystemPrompt(String stable, String environment) {
    public SystemPrompt {
        stable = stable == null ? "" : stable;
        environment = environment == null ? "" : environment;
    }
}
