package com.bluecode.tui;

import com.bluecode.agent.SessionRuntime;
import com.bluecode.team.TeamManager;

import java.util.List;
import java.util.function.Consumer;

public final class LeadMailWatcher {
    private LeadMailWatcher() {
    }

    public static void start(TeamManager teamManager, SessionRuntime runtime, Consumer<LeadMailEvent> sink) {
        if (teamManager == null || runtime == null || sink == null) {
            return;
        }
        Thread.startVirtualThread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1000);
                    List<TeamManager.LeadMessage> messages = teamManager.pollLeadMailboxes();
                    if (messages.isEmpty()) {
                        continue;
                    }
                    runtime.appendReminders(List.of(buildTeamUpdateReminder(messages)));
                    sink.accept(new LeadMailEvent("[team-update] 队员发来新消息，请按 Coordinator 流程处理。"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println("[team] warn: lead mail watcher failed: " + e.getMessage());
                }
            }
        });
    }

    private static String buildTeamUpdateReminder(List<TeamManager.LeadMessage> messages) {
        StringBuilder builder = new StringBuilder();
        builder.append("<team-update>\n");
        for (TeamManager.LeadMessage message : messages) {
            builder.append("- team: ").append(message.teamName()).append('\n');
            builder.append("  from: ").append(message.from()).append('\n');
            builder.append("  type: ").append(message.type().wireValue()).append('\n');
            builder.append("  summary: ").append(message.summary()).append('\n');
            builder.append("  content: ").append(truncate(message.content(), 8000)).append('\n');
        }
        builder.append("</team-update>");
        return builder.toString();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 15)) + "...[truncated]";
    }
}
