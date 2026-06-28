package com.bluecode.command;

import java.util.List;

public final class Builtins {
    private Builtins() {
    }

    public static void registerAll(CommandRegistry registry) {
        registry.register(command("clear", "清空当前会话并开启新会话", Kind.UI, BuiltinUi.clear()));
        registry.register(command("compact", "手动压缩当前上下文", Kind.UI, BuiltinUi.compact()));
        registry.register(command("do", "切回执行模式并按计划开始执行", Kind.PROMPT, BuiltinPrompt.doRun()));
        registry.register(command("exit", "退出 BlueCode", Kind.UI, BuiltinUi.exit()));
        registry.register(command("help", "显示所有可用命令", Kind.LOCAL, BuiltinLocal.help(registry)));
        registry.register(command("hooks", "列出当前已加载的 hook", Kind.LOCAL, BuiltinLocal.hooks()));
        registry.register(command("memory", "列出当前加载的记忆文件", Kind.LOCAL, BuiltinLocal.memory()));
        registry.register(command("permission", "显示当前权限模式", Kind.LOCAL, BuiltinLocal.permission()));
        registry.register(command("plan", "切换到计划模式", Kind.UI, BuiltinUi.plan()));
        registry.register(command("resume", "查看可恢复会话", Kind.UI, BuiltinUi.resume()));
        registry.register(command("review", "注入代码审查请求并开始回复", Kind.PROMPT, BuiltinPrompt.review()));
        registry.register(command("session", "显示当前会话标识和路径", Kind.LOCAL, BuiltinLocal.session()));
        registry.register(command("status", "显示当前运行状态", Kind.LOCAL, BuiltinLocal.status()));
        registry.register(command("worktree", "管理 Git Worktree 隔离副本", Kind.UI, WorktreeCommand.handler()));
    }

    private static Command command(String name, String description, Kind kind, Command.Handler handler) {
        return new Command(name, List.of(), description, kind, false, handler);
    }
}
