package com.bluecode.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderTest {
    @Test
    void wrapsSystemReminderWithTags() {
        String reminder = Reminder.systemReminder("保持计划模式");

        assertTrue(reminder.startsWith("<system-reminder>"));
        assertTrue(reminder.endsWith("</system-reminder>"));
        assertTrue(reminder.contains("保持计划模式"));
    }

    @Test
    void planReminderHasFullAndConciseForms() {
        String full = Reminder.plan(true);
        String concise = Reminder.plan(false);

        assertTrue(full.contains("当前处于 PLAN MODE"));
        assertTrue(full.contains("<system-reminder>"));
        assertTrue(concise.contains("PLAN MODE 仍然生效"));
        assertFalse(concise.contains("当前处于 PLAN MODE"));
    }
}
