package com.bluecode.worktree;

import java.util.regex.Pattern;

public final class WorktreeSlug {
    private static final Pattern SEGMENT = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final int MAX_LENGTH = 64;

    private WorktreeSlug() {
    }

    public static void validate(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Worktree 名称不能为空");
        }
        if (name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Worktree 名称不能超过 " + MAX_LENGTH + " 个字符");
        }
        if (name.startsWith("/") || name.endsWith("/")) {
            throw new IllegalArgumentException("Worktree 名称不能以 / 开头或结尾");
        }
        if (name.contains("//")) {
            throw new IllegalArgumentException("Worktree 名称不能包含连续的 /");
        }
        for (String segment : name.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Worktree 名称不能包含 . 或 .. 段");
            }
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("Worktree 名称只能包含字母、数字、点、下划线、短横线和 /");
            }
        }
    }

    public static String flatten(String name) {
        validate(name);
        return name.replace("/", "+");
    }
}
