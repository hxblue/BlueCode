package com.bluecode.permission;

import java.util.Locale;
import java.util.Optional;

public enum Mode {
    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    PLAN("plan"),
    BYPASS("bypassPermissions");

    private final String displayName;

    Mode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<Mode> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "default", "normal" -> Optional.of(DEFAULT);
            case "acceptedits" -> Optional.of(ACCEPT_EDITS);
            case "plan" -> Optional.of(PLAN);
            case "bypass", "bypasspermissions" -> Optional.of(BYPASS);
            default -> Optional.empty();
        };
    }
}
