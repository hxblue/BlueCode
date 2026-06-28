package com.bluecode.command;

import com.bluecode.permission.Mode;
import com.bluecode.prompt.Reminder;

final class BuiltinPrompt {
    static final String REVIEW_DIRECTIVE = "请审查当前上下文中的代码变更/已读取的文件,指出潜在 bug、可读性问题和可简化处。";

    private BuiltinPrompt() {
    }

    static Command.Handler doRun() {
        return (cancelled, ui) -> {
            ui.setMode(Mode.DEFAULT);
            ui.injectAndSend("/do", Reminder.EXECUTE_DIRECTIVE);
        };
    }

    static Command.Handler review() {
        return (cancelled, ui) -> ui.injectAndSend("/review", REVIEW_DIRECTIVE);
    }
}
