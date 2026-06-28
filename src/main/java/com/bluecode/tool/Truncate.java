package com.bluecode.tool;

import java.nio.charset.StandardCharsets;

public final class Truncate {
    private Truncate() {
    }

    public static String byLinesAndBytes(String text, int maxLines, int maxBytes) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int lines = 0;
        boolean truncated = false;
        for (String line : text.split("\\R", -1)) {
            if (lines >= maxLines) {
                truncated = true;
                break;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line);
            lines++;
            if (out.toString().getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                truncated = true;
                out = new StringBuilder(trimUtf8(out.toString(), maxBytes));
                break;
            }
        }
        if (truncated && !out.toString().endsWith("[truncated]")) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append("[truncated]");
        }
        return out.toString();
    }

    public static String byChars(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, Math.max(0, maxChars)) + "\n[truncated]";
    }

    private static String trimUtf8(String text, int maxBytes) {
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String chunk = new String(Character.toChars(codePoint));
            int bytes = chunk.getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > maxBytes) {
                break;
            }
            result.append(chunk);
            used += bytes;
            i += Character.charCount(codePoint);
        }
        return result.toString().stripTrailing();
    }
}
