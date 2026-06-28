package com.bluecode.tui;

import com.bluecode.command.Command;
import com.bluecode.command.CommandRegistry;

import java.util.List;

public final class CompletionMenu {
    static final int MAX_ROWS = 8;
    private static final String NO_MATCH = "无匹配";

    private List<Command> items = List.of();
    private int cursor;
    private int offset;
    private boolean active;

    public void update(String input, CommandRegistry registry) {
        String text = input == null ? "" : input;
        if (!text.startsWith("/") || text.contains("\n")) {
            hide();
            return;
        }
        items = registry.prefixMatch(text.strip());
        active = true;
        clamp();
    }

    public void moveUp() {
        if (items.isEmpty()) {
            return;
        }
        cursor = Math.max(0, cursor - 1);
        clampOffset();
    }

    public void moveDown() {
        if (items.isEmpty()) {
            return;
        }
        cursor = Math.min(items.size() - 1, cursor + 1);
        clampOffset();
    }

    public Command selected() {
        if (items.isEmpty()) {
            return null;
        }
        return items.get(cursor);
    }

    public void hide() {
        active = false;
        items = List.of();
        cursor = 0;
        offset = 0;
    }

    public boolean active() {
        return active;
    }

    public int lineCount() {
        if (!active) {
            return 0;
        }
        if (items.isEmpty()) {
            return 1;
        }
        return Math.min(MAX_ROWS, items.size());
    }

    public String render(int width) {
        if (!active) {
            return "";
        }
        if (items.isEmpty()) {
            return Styles.MUTED.render(fit(NO_MATCH, width));
        }
        int end = Math.min(items.size(), offset + MAX_ROWS);
        int nameWidth = items.stream()
                .mapToInt(command -> command.name().length())
                .max()
                .orElse(0);
        StringBuilder builder = new StringBuilder();
        for (int i = offset; i < end; i++) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            Command command = items.get(i);
            String marker = i == cursor ? "> " : "  ";
            String line = marker + "/" + command.name()
                    + " ".repeat(Math.max(1, nameWidth - command.name().length() + 2))
                    + command.description();
            String fitted = fit(line, width);
            builder.append(i == cursor ? Styles.STATUS.render(fitted) : Styles.MUTED.render(fitted));
        }
        return builder.toString();
    }

    private void clamp() {
        if (items.isEmpty()) {
            cursor = 0;
            offset = 0;
            return;
        }
        cursor = Math.min(cursor, items.size() - 1);
        clampOffset();
    }

    private void clampOffset() {
        if (cursor < offset) {
            offset = cursor;
        } else if (cursor >= offset + MAX_ROWS) {
            offset = cursor - MAX_ROWS + 1;
        }
    }

    private String fit(String text, int width) {
        int max = Math.max(10, width - 2);
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(1, max - 3)) + "...";
    }
}
