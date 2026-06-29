package com.bluecode.teams;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum Status {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    BLOCKED("blocked");

    private final String wire;

    Status(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wireValue() {
        return wire;
    }

    @JsonCreator
    public static Status fromWire(String value) {
        if (value == null || value.isBlank()) {
            return PENDING;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (Status status : values()) {
            if (status.wire.equals(normalized) || status.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知任务状态: " + value);
    }
}
