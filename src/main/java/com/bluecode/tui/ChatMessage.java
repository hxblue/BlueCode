package com.bluecode.tui;

public record ChatMessage(Role role, String content, long elapsedMillis, boolean error) {
    public enum Role {
        USER,
        ASSISTANT,
        TOOL,
        SYSTEM
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, 0, false);
    }

    public static ChatMessage assistant(String content, long elapsedMillis) {
        return new ChatMessage(Role.ASSISTANT, content, elapsedMillis, false);
    }

    public static ChatMessage tool(String content, boolean error) {
        return new ChatMessage(Role.TOOL, content, 0, error);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content, 0, false);
    }

    public static ChatMessage error(String content) {
        return new ChatMessage(Role.SYSTEM, content, 0, true);
    }
}
