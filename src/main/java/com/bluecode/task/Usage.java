package com.bluecode.task;

public record Usage(long input, long output, long cacheWrite, long cacheRead) {
    public Usage plus(com.bluecode.agent.Usage usage) {
        if (usage == null) {
            return this;
        }
        return new Usage(
                input + usage.input(),
                output + usage.output(),
                cacheWrite + usage.cacheWrite(),
                cacheRead + usage.cacheRead());
    }
}
