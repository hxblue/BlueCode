package com.bluecode.command;

import com.bluecode.skill.SkillCatalog;
import com.bluecode.skill.SkillExecutor;

import java.util.List;

public final class SkillCommands {
    public static final String SKILL_MARKER = "[skill]";

    private SkillCommands() {
    }

    public static void registerSkillsAsCommands(CommandRegistry registry, SkillCatalog catalog) {
        if (registry == null || catalog == null) {
            return;
        }
        for (SkillCatalog.Skill skill : catalog.list()) {
            registerSkillCommand(registry, catalog, skill.name(), skill.meta().description());
        }
    }

    public static void removeSkillCommands(CommandRegistry registry) {
        if (registry != null) {
            registry.removeIf(command -> isSkillCommand(command));
        }
    }

    public static void registerSkillListCommand(CommandRegistry registry, SkillCatalog catalog) {
        if (registry == null || registry.lookup("skills").isPresent()) {
            return;
        }
        registry.register(new Command(
                "skills",
                List.of(),
                "列出当前已发现的 skills",
                Kind.LOCAL,
                false,
                (cancelled, ui) -> ui.println(renderSkills(catalog))));
    }

    public static boolean isSkillCommand(Command command) {
        return command != null && command.description() != null && command.description().endsWith(SKILL_MARKER);
    }

    private static void registerSkillCommand(CommandRegistry registry, SkillCatalog catalog, String name, String description) {
        String normalized = SkillCatalog.normalizeName(name);
        if (normalized.isBlank() || registry.lookup(normalized).isPresent()) {
            return;
        }
        String desc = (description == null || description.isBlank() ? "BlueCode skill" : description.strip())
                + " "
                + SKILL_MARKER;
        registry.register(new Command(
                normalized,
                List.of(),
                desc,
                Kind.PROMPT,
                false,
                (cancelled, ui) -> catalog.getFull(normalized)
                        .ifPresentOrElse(
                                skill -> ui.injectAndSend("/" + normalized,
                                        SkillExecutor.substituteArguments(skill.promptBody(), "")),
                                () -> ui.error("unknown skill: " + normalized))));
    }

    private static String renderSkills(SkillCatalog catalog) {
        if (catalog == null || catalog.list().isEmpty()) {
            return "No skills installed.\n\nAdd skills to .bluecode/skills/<name>/SKILL.md";
        }
        StringBuilder builder = new StringBuilder();
        for (SkillCatalog.Skill skill : catalog.list()) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append("/")
                    .append(skill.name());
            String description = skill.meta().description();
            if (description != null && !description.isBlank()) {
                builder.append(" - ").append(description);
            }
        }
        return builder.toString();
    }
}
