package com.bluecode.tool;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Filter {
    public static final List<String> ALL_AGENT_DISALLOWED_TOOLS = List.of("Agent");
    public static final List<String> CUSTOM_AGENT_DISALLOWED_TOOLS = List.of();
    public static final List<String> ASYNC_AGENT_ALLOWED_TOOLS = List.of(
            "ReadFile",
            "WriteFile",
            "EditFile",
            "Glob",
            "Grep",
            "Bash",
            "LoadSkill",
            "InstallSkill");
    public static final List<String> TEAMMATE_ALLOWED_TOOLS = List.of(
            "TaskCreate",
            "TaskGet",
            "TaskList",
            "TaskUpdate",
            "SendMessage");

    private Filter() {
    }

    public record FilterParams(
            List<String> all,
            int source,
            boolean background,
            List<String> allowed,
            List<String> disallowed,
            boolean teammate) {
        public FilterParams(List<String> all, int source, boolean background, List<String> allowed, List<String> disallowed) {
            this(all, source, background, allowed, disallowed, false);
        }

        public FilterParams {
            all = all == null ? List.of() : List.copyOf(all);
            allowed = allowed == null ? List.of() : List.copyOf(allowed);
            disallowed = disallowed == null ? List.of() : List.copyOf(disallowed);
        }
    }

    public static List<String> applyAgentToolFilter(FilterParams params) {
        LinkedHashSet<String> current = new LinkedHashSet<>(params.all());
        current.removeAll(ALL_AGENT_DISALLOWED_TOOLS);

        if (params.source() >= 2) {
            current.removeAll(CUSTOM_AGENT_DISALLOWED_TOOLS);
        }

        if (params.background()) {
            Set<String> asyncAllowed = new LinkedHashSet<>(ASYNC_AGENT_ALLOWED_TOOLS);
            if (params.teammate()) {
                asyncAllowed.addAll(TEAMMATE_ALLOWED_TOOLS);
            }
            current.removeIf(name -> !asyncAllowed.contains(name) && !isMcpOrSkill(name));
        }

        if (!params.teammate()) {
            current.removeAll(TEAMMATE_ALLOWED_TOOLS);
        }

        current.removeAll(params.disallowed());

        if (!params.allowed().isEmpty()) {
            Set<String> allowed = new LinkedHashSet<>(params.allowed());
            current.removeIf(name -> !allowed.contains(name));
        }

        return List.copyOf(new ArrayList<>(current));
    }

    public static boolean isMcpOrSkill(String name) {
        if (name == null) {
            return false;
        }
        return name.startsWith("mcp__") || name.equals("LoadSkill") || name.equals("InstallSkill");
    }
}
