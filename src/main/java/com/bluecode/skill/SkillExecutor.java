package com.bluecode.skill;

import com.bluecode.conversation.Message;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

public final class SkillExecutor {
    public static final int FORK_RECENT_COUNT = 5;

    private SkillExecutor() {
    }

    public static String executeInline(SkillCatalog.Skill skill, String args, SkillHost host) {
        if (skill == null) {
            throw new IllegalArgumentException("skill 不能为空");
        }
        if (host == null) {
            throw new IllegalArgumentException("skill host 不能为空");
        }
        assertAllowedToolsExist(skill, host);
        String body = substituteArguments(skill.promptBody(), args);
        host.activateSkill(skill.name(), body);
        List<String> allowed = skill.meta().allowedTools();
        Predicate<String> filter = allowed.isEmpty()
                ? ignored -> true
                : name -> allowed.contains(name) || host.toolRegistry().get(name).map(tool -> tool.isSystem()).orElse(false);
        host.setToolFilter(filter);
        return body;
    }

    public static String executeFork(SkillCatalog.Skill skill, String args, SkillForkHost host) {
        if (skill == null) {
            throw new IllegalArgumentException("skill 不能为空");
        }
        if (host == null) {
            throw new IllegalArgumentException("skill fork host 不能为空");
        }
        assertAllowedToolsExist(skill, host);
        String body = substituteArguments(skill.promptBody(), args);
        List<Message> seed = buildForkSeed(skill.meta().forkContext(), host.snapshotParentMessages());
        return host.runSubAgent(body, seed, skill.meta().allowedTools(), skill.meta().model());
    }

    public static String substituteArguments(String body, String args) {
        String prompt = body == null ? "" : body;
        String value = args == null ? "" : args.strip();
        if (value.isBlank()) {
            return prompt;
        }
        if (prompt.contains("$ARGUMENTS")) {
            return prompt.replace("$ARGUMENTS", value);
        }
        return prompt.stripTrailing() + "\n\n## User Request\n\n" + value;
    }

    public static List<Message> buildForkSeed(String mode, List<Message> parent) {
        List<Message> messages = parent == null ? List.of() : List.copyOf(parent);
        String normalized = mode == null ? "none" : mode.strip().toLowerCase(Locale.ROOT);
        if ("full".equals(normalized)) {
            return messages;
        }
        if ("recent".equals(normalized)) {
            int from = Math.max(0, messages.size() - FORK_RECENT_COUNT);
            return List.copyOf(messages.subList(from, messages.size()));
        }
        return List.of();
    }

    public static void assertAllowedToolsExist(SkillCatalog.Skill skill, SkillHost host) {
        if (skill == null || host == null || skill.meta().allowedTools().isEmpty()) {
            return;
        }
        Set<String> missing = new HashSet<>();
        for (String tool : skill.meta().allowedTools()) {
            if (host.toolRegistry().get(tool).isEmpty()) {
                missing.add(tool);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("skill " + skill.name() + " references missing tools: "
                    + String.join(", ", new ArrayList<>(missing)));
        }
    }
}
