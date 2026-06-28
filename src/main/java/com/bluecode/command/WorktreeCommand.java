package com.bluecode.command;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorktreeCommand {
    private WorktreeCommand() {
    }

    public static Command.Handler handler() {
        return WorktreeCommand::handle;
    }

    private static void handle(AtomicBoolean cancelled, Ui ui) throws Exception {
        WorktreeAccessor accessor = ui.worktreeAccessor();
        if (accessor == null) {
            ui.error("Worktree 功能未启用");
            return;
        }
        List<String> parts = split(ui.commandArguments());
        if (parts.isEmpty()) {
            ui.println(help());
            return;
        }
        String sub = parts.getFirst();
        switch (sub) {
            case "create" -> {
                requireSize(parts, 2, "用法: /worktree create <slug>");
                WorktreeAccessor.CreateResult result = accessor.create(parts.get(1));
                ui.println("Worktree 已创建: " + result.path() + " (分支 " + result.branch() + ")");
            }
            case "list" -> {
                List<WorktreeSummary> items = accessor.list();
                if (items.isEmpty()) {
                    ui.println("暂无 Worktree");
                    return;
                }
                ui.println(String.join("\n", items.stream().map(WorktreeCommand::format).toList()));
            }
            case "enter" -> {
                requireSize(parts, 2, "用法: /worktree enter <slug>");
                accessor.enter(parts.get(1));
                ui.println("已进入 " + parts.get(1));
            }
            case "exit" -> {
                boolean remove = parts.contains("--remove");
                boolean discard = parts.contains("--discard");
                WorktreeAccessor.ExitResult result = accessor.exit(remove, discard);
                ui.println(remove || result.removed()
                        ? "已退出并删除 Worktree: " + result.path()
                        : "已退出 Worktree: " + result.path());
            }
            case "remove" -> {
                requireSize(parts, 2, "用法: /worktree remove <slug> [--discard]");
                accessor.remove(parts.get(1), parts.contains("--discard"));
                ui.println("已删除 Worktree: " + parts.get(1));
            }
            default -> ui.error("未知 /worktree 子命令: " + sub + "\n" + help());
        }
    }

    private static List<String> split(String args) {
        if (args == null || args.isBlank()) {
            return List.of();
        }
        return List.of(args.strip().split("\\s+"));
    }

    private static void requireSize(List<String> parts, int size, String message) {
        if (parts.size() < size) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String format(WorktreeSummary item) {
        String flags = (item.active() ? " [active]" : "") + (item.manual() ? " [manual]" : "");
        return item.name() + "  " + item.path() + "  " + item.branch() + flags;
    }

    private static String help() {
        return """
                /worktree create <slug>
                /worktree list
                /worktree enter <slug>
                /worktree exit [--remove] [--discard]
                /worktree remove <slug> [--discard]
                """.strip();
    }
}
