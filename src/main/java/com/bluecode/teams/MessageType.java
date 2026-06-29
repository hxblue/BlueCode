package com.bluecode.teams;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum MessageType {
    TEXT("text"),
    SHUTDOWN_REQUEST("shutdown_request"),
    SHUTDOWN_RESPONSE("shutdown_response"),
    PLAN_APPROVAL_RESPONSE("plan_approval_response"),
    IDLE_NOTIFICATION("idle_notification");

    private final String wire;

    MessageType(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wireValue() {
        return wire;
    }

    @JsonCreator
    public static MessageType fromWire(String value) {
        if (value == null || value.isBlank()) {
            return TEXT;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (MessageType type : values()) {
            if (type.wire.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知消息类型: " + value);
    }
}
