package com.bluecode.task;

public record PartialState(String lastAssistantText, int toolCount, String lastActivity, Usage usage) {
}
