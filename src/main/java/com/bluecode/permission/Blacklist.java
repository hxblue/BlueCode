package com.bluecode.permission;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 内置危险命令黑名单。它是启发式防线，不追求穷尽所有危险命令，也不提供配置入口绕过。
 */
public final class Blacklist {
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("\\brm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+(?:/\\*|/|~|\\$HOME)(?:\\s|$|[;&|])", FLAGS),
            Pattern.compile("\\bdd\\b.*\\bof=/dev/(?:sd|hd|vd|xvd|nvme|disk|rdisk)\\w*", FLAGS),
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*}\\s*;?\\s*:", FLAGS),
            Pattern.compile("\\bmkfs(?:\\.[\\w-]+)?\\b", FLAGS),
            Pattern.compile(">\\s*/dev/(?:sd|hd|vd|xvd|nvme|disk|rdisk)\\w*", FLAGS),
            Pattern.compile("\\bchmod\\s+-R\\s+0?777\\s+(?:/|~|\\$HOME)(?:\\s|$|[;&|])", FLAGS),
            Pattern.compile("\\bformat\\s+[a-z]:", FLAGS),
            Pattern.compile("\\bdel\\s+/[sq]\\s+[a-z]:\\\\", FLAGS)
    );

    private Blacklist() {
    }

    public static boolean hitsBlacklist(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        return PATTERNS.stream().anyMatch(pattern -> pattern.matcher(command).find());
    }
}
