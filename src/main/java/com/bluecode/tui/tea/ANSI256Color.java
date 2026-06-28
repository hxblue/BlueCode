package com.bluecode.tui.tea;

public enum ANSI256Color {
    DEFAULT(-1),
    BLACK(0),
    RED(160),
    GREEN(34),
    YELLOW(220),
    BLUE(33),
    MAGENTA(170),
    CYAN(37),
    GRAY(245),
    BRIGHT_GRAY(250),
    WHITE(255);

    private final int code;

    ANSI256Color(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
