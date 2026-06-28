package com.bluecode.command;

import com.bluecode.permission.Mode;

final class BuiltinUi {
    private BuiltinUi() {
    }

    static Command.Handler exit() {
        return (cancelled, ui) -> ui.quit();
    }

    static Command.Handler plan() {
        return (cancelled, ui) -> {
            ui.setMode(Mode.PLAN);
            ui.println("已切换到 PLAN 模式");
        };
    }

    static Command.Handler compact() {
        return (cancelled, ui) -> {
            if (!ui.idle()) {
                ui.error("请等待当前任务完成");
                return;
            }
            ui.forceCompact();
        };
    }

    static Command.Handler resume() {
        return (cancelled, ui) -> {
            if (!ui.idle()) {
                ui.error("请等待当前任务完成");
                return;
            }
            ui.openResumeMenu();
        };
    }

    static Command.Handler clear() {
        return (cancelled, ui) -> {
            ui.clearAndNewSession();
            ui.clearActiveSkills();
            ui.println("已清空当前会话,开启新 session");
        };
    }
}
