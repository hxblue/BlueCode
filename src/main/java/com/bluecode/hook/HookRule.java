package com.bluecode.hook;

import java.time.Duration;

public record HookRule(
        String name,
        Event event,
        Condition condition,
        Action action,
        boolean onlyOnce,
        boolean async,
        Duration timeout,
        String source) {
    public HookRule {
        name = name == null ? "" : name.strip();
        if (name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (event == null) {
            throw new IllegalArgumentException("event 不能为空");
        }
        if (action == null) {
            throw new IllegalArgumentException("action 不能为空");
        }
        timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        source = source == null ? "" : source;
    }
}
