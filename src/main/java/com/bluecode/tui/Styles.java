package com.bluecode.tui;

import com.bluecode.tui.tea.ANSI256Color;
import com.bluecode.tui.tea.Style;

public final class Styles {
    public static final Style TITLE = new Style().foreground(ANSI256Color.CYAN).bold();
    public static final Style MUTED = new Style().foreground(ANSI256Color.GRAY);
    public static final Style USER = new Style().foreground(ANSI256Color.GREEN).bold();
    public static final Style ASSISTANT = new Style().foreground(ANSI256Color.CYAN).bold();
    public static final Style TOOL = new Style().foreground(ANSI256Color.GREEN).bold();
    public static final Style TOOL_RESULT = new Style().foreground(ANSI256Color.GRAY);
    public static final Style ERROR = new Style().foreground(ANSI256Color.RED).bold();
    public static final Style STATUS = new Style().foreground(ANSI256Color.BLACK).background(ANSI256Color.BRIGHT_GRAY);

    private Styles() {
    }
}
