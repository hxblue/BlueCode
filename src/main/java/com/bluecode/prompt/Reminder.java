package com.bluecode.prompt;

public final class Reminder {
    public static final String EXECUTE_DIRECTIVE = "请按上面的计划开始执行。";

    private static final String PLAN_REMINDER_FULL = """
            当前处于 PLAN MODE。你只能使用只读工具(ReadFile、Glob、Grep)调研代码库。
            不要写文件、不要编辑文件、不要执行 shell 命令。
            请产出清晰的分步执行计划，计划写完后停止，等待用户用 /do 批准后再动手。
            这是一条系统补充提醒，不是用户问题；不要复述本提醒内容。
            """.strip();

    private static final String PLAN_REMINDER_CONCISE = """
            PLAN MODE 仍然生效：只使用只读工具调研并完善计划，等待 /do 后再执行写入或命令。
            不要复述本提醒内容。
            """.strip();

    private Reminder() {
    }

    public static String systemReminder(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return "<system-reminder>\n" + body.strip() + "\n</system-reminder>";
    }

    public static String plan(boolean full) {
        return systemReminder(full ? PLAN_REMINDER_FULL : PLAN_REMINDER_CONCISE);
    }
}
