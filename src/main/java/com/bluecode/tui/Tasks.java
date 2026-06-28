package com.bluecode.tui;

import com.bluecode.task.BackgroundTask;

public final class Tasks {
    private Tasks() {
    }

    public static String buildTaskNotification(BackgroundTask task) {
        String name = task.name().isBlank() ? "" : " (name=\"" + task.name() + "\")";
        String body;
        if (task.err() != null) {
            body = "Error: " + task.err().getMessage();
        } else {
            body = "Result: " + task.result();
        }
        return """
                <task-notification>
                Task %s%s: %s
                %s
                </task-notification>
                """.formatted(task.id(), name, task.status().name().toLowerCase(), body).strip();
    }
}
