package com.bluecode.memory;

public record UpdateAction(
        String action,
        String level,
        String type,
        String title,
        String slug,
        String content,
        String filename) {
}
