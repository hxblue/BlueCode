package com.bluecode.command;

import com.bluecode.hook.Event;
import com.bluecode.hook.HookRule;

import java.util.ArrayList;
import java.util.List;

final class BuiltinLocal {
    private BuiltinLocal() {
    }

    static Command.Handler help(CommandRegistry registry) {
        return (cancelled, ui) -> {
            List<Command> commands = registry.visible();
            int width = commands.stream()
                    .mapToInt(command -> command.name().length())
                    .max()
                    .orElse(0);
            StringBuilder builder = new StringBuilder();
            for (Command command : commands) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append("/")
                        .append(command.name())
                        .append(" ".repeat(Math.max(1, width - command.name().length() + 2)))
                        .append(command.description());
            }
            ui.println(builder.toString());
        };
    }

    static Command.Handler status() {
        return (cancelled, ui) -> ui.println("""
                BlueCode Status

                Mode:      %s
                Tokens:    %d in / %d out
                Tools:     %d enabled
                Memories:  %d files
                Model:     %s
                Directory: %s
                """.formatted(
                ui.mode().displayName(),
                ui.usageIn(),
                ui.usageOut(),
                ui.toolCount(),
                ui.memoryFiles().size(),
                ui.modelName(),
                ui.cwd()).stripTrailing());
    }

    static Command.Handler memory() {
        return (cancelled, ui) -> {
            List<String> files = ui.memoryFiles();
            ui.println(files.isEmpty() ? "无已加载的记忆文件" : String.join("\n", files));
        };
    }

    static Command.Handler permission() {
        return (cancelled, ui) -> ui.println(ui.mode().displayName());
    }

    static Command.Handler session() {
        return (cancelled, ui) -> ui.println("Session: " + ui.sessionId() + "\nPath: " + ui.sessionPath());
    }

    static Command.Handler hooks() {
        return (cancelled, ui) -> {
            List<HookRule> rules = ui.hookRules();
            if (rules.isEmpty()) {
                ui.println("No hooks loaded.");
                return;
            }
            List<String> lines = new ArrayList<>();
            for (Event event : Event.values()) {
                List<HookRule> group = rules.stream().filter(rule -> rule.event() == event).toList();
                if (group.isEmpty()) {
                    continue;
                }
                lines.add(event.wireName());
                for (HookRule rule : group) {
                    String flags = flags(rule);
                    lines.add("  %s  %s  %s%s".formatted(
                            rule.name(),
                            rule.event().wireName(),
                            rule.action().typeName(),
                            flags.isBlank() ? "" : "  " + flags));
                }
            }
            List<String> sources = ui.hookSources();
            lines.add("Loaded from: " + (sources.isEmpty() ? "-" : String.join(", ", sources)));
            ui.println(String.join("\n", lines));
        };
    }

    private static String flags(HookRule rule) {
        List<String> flags = new ArrayList<>();
        if (rule.onlyOnce()) {
            flags.add("[once]");
        }
        if (rule.async()) {
            flags.add("[async]");
        }
        return String.join(" ", flags);
    }
}
