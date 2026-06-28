package com.bluecode.agent;

// Agent 对 UI 暴露的一轮 token 用量。
public record Usage(long input, long output, long cacheWrite, long cacheRead) {
    public Usage(long input, long output) {
        this(input, output, 0, 0);
    }
}
