package com.bluecode.command;

import java.util.Locale;

public final class Dispatch {
    private Dispatch() {
    }

    public record Parsed(String name, boolean isSlash, String arguments) {
    }

    public static Parsed parse(String input) {
        String text = input == null ? "" : input.strip();
        if (text.isEmpty() || !text.startsWith("/")) {
            return new Parsed("", false, "");
        }
        String tail = text.substring(1);
        if (tail.isEmpty()) {
            return new Parsed("", true, "");
        }
        int split = firstWhitespace(tail);
        if (split == 0) {
            return new Parsed("", true, tail.strip());
        }
        if (split < 0) {
            return new Parsed(tail.toLowerCase(Locale.ROOT), true, "");
        }
        String name = tail.substring(0, split);
        String rest = tail.substring(split).strip();
        return new Parsed(name.toLowerCase(Locale.ROOT), true, rest);
    }

    private static int firstWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
