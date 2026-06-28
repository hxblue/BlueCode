package com.bluecode.tui.tea;

public record KeyPressMessage(String key, char[] runes) implements Message {
    public boolean hasText() {
        return runes != null && runes.length > 0;
    }
}
