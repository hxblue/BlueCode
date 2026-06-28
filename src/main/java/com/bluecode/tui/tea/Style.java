package com.bluecode.tui.tea;

public final class Style {
    private ANSI256Color foreground = ANSI256Color.DEFAULT;
    private ANSI256Color background = ANSI256Color.DEFAULT;
    private boolean bold;
    private boolean dim;

    public Style foreground(ANSI256Color color) {
        this.foreground = color;
        return this;
    }

    public Style background(ANSI256Color color) {
        this.background = color;
        return this;
    }

    public Style bold() {
        this.bold = true;
        return this;
    }

    public Style dim() {
        this.dim = true;
        return this;
    }

    public String render(String text) {
        StringBuilder builder = new StringBuilder();
        if (bold) {
            builder.append("\u001B[1m");
        }
        if (dim) {
            builder.append("\u001B[2m");
        }
        if (foreground.code() >= 0) {
            builder.append("\u001B[38;5;").append(foreground.code()).append('m');
        }
        if (background.code() >= 0) {
            builder.append("\u001B[48;5;").append(background.code()).append('m');
        }
        builder.append(text).append("\u001B[0m");
        return builder.toString();
    }
}
