package com.bluecode.llm;

// 协议无关的一轮模型请求 token 用量。
public record Usage(long inputTokens, long outputTokens, long cacheWrite, long cacheRead) {
    public Usage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, 0, 0);
    }
}
