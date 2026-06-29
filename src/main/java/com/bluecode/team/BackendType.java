package com.bluecode.team;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum BackendType {
    TMUX("tmux"),
    ITERM2("iterm2"),
    IN_PROCESS("in-process");

    private final String wire;

    BackendType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wireValue() {
        return wire;
    }

    @JsonCreator
    public static BackendType fromWire(String value) {
        if (value == null || value.isBlank()) {
            return IN_PROCESS;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (BackendType type : values()) {
            if (type.wire.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知 Team 后端类型: " + value);
    }
}
