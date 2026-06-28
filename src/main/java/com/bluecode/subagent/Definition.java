package com.bluecode.subagent;

import com.bluecode.permission.Mode;

import java.util.List;

/**
 * Definition 对应一个 Markdown + YAML frontmatter 的子 Agent 角色定义。
 */
public record Definition(
        String name,
        String description,
        List<String> tools,
        List<String> disallowedTools,
        String model,
        int maxTurns,
        Mode permissionMode,
        boolean dontAsk,
        boolean background,
        String systemPrompt,
        String filePath,
        Source source,
        String isolation) {

    public Definition {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        tools = tools == null ? List.of() : List.copyOf(tools);
        disallowedTools = disallowedTools == null ? List.of() : List.copyOf(disallowedTools);
        model = model == null || model.isBlank() ? "inherit" : model;
        maxTurns = Math.max(0, maxTurns);
        permissionMode = permissionMode == null ? Mode.DEFAULT : permissionMode;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        filePath = filePath == null ? "" : filePath;
        source = source == null ? Source.BUILTIN : source;
        isolation = "worktree".equals(isolation) ? "worktree" : "";
    }

    public boolean isFork() {
        return "__fork__".equals(name);
    }

    public enum Source {
        BUILTIN,
        USER,
        PROJECT,
        PLUGIN;

        @Override
        public String toString() {
            return switch (this) {
                case BUILTIN -> "builtin";
                case USER -> "user";
                case PROJECT -> "project";
                case PLUGIN -> "plugin";
            };
        }
    }
}
