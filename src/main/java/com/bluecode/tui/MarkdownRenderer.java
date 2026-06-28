package com.bluecode.tui;

import com.bluecode.tui.tea.ANSI256Color;
import com.bluecode.tui.tea.Style;

public class MarkdownRenderer {
    public String render(String markdown, int width) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        StringBuilder rendered = new StringBuilder();
        boolean code = false;
        for (String line : markdown.split("\\R", -1)) {
            if (line.startsWith("```")) {
                code = !code;
                rendered.append(new Style().foreground(ANSI256Color.GRAY).render(line)).append('\n');
                continue;
            }
            if (code) {
                rendered.append(new Style().foreground(ANSI256Color.YELLOW).render(line)).append('\n');
            } else if (line.startsWith("#")) {
                rendered.append(new Style().foreground(ANSI256Color.CYAN).bold().render(line.replaceFirst("^#+\\s*", ""))).append('\n');
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                rendered.append("  • ").append(line.substring(2)).append('\n');
            } else {
                rendered.append(line).append('\n');
            }
        }
        return rendered.toString().stripTrailing();
    }
}
