package com.bluecode.prompt;

public record Module(String name, int priority, String content) {
    public Module {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("模块名称不能为空");
        }
        content = content == null ? "" : content.strip();
    }
}
