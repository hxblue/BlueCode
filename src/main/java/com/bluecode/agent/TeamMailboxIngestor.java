package com.bluecode.agent;

import com.bluecode.agent.TeammateContext.IncomingMessage;
import com.bluecode.permission.Mode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TeamMailboxIngestor {
    private TeamMailboxIngestor() {
    }

    public static void ingest(Agent agent, SessionRuntime runtime) {
        if (agent == null || runtime == null || agent.teammateContext() == null) {
            return;
        }
        TeammateContext context = agent.teammateContext();
        if (context.readUnread() == null) {
            return;
        }
        TeammateContext.ReadUnreadView unread = context.readUnread().get();
        if (unread == null || unread.messages().isEmpty()) {
            return;
        }
        runtime.appendReminders(List.of(buildReminder(context, unread.messages(), agent)));
        if (context.markRead() != null) {
            context.markRead().accept(unread.indices());
        }
    }

    private static String buildReminder(TeammateContext context, List<IncomingMessage> messages, Agent agent) {
        List<String> lines = new ArrayList<>();
        lines.add("<incoming-messages>");
        lines.add("team: " + context.teamName());
        lines.add("member: " + context.memberName());
        for (IncomingMessage message : messages) {
            lines.add("- from: " + message.from());
            lines.add("  type: " + message.type());
            lines.add("  summary: " + message.summary());
            lines.add("  content: " + message.content());
            handlePlanApproval(message, agent, lines);
        }
        lines.add("</incoming-messages>");
        return String.join("\n", lines);
    }

    private static void handlePlanApproval(IncomingMessage message, Agent agent, List<String> lines) {
        if (!"plan_approval_response".equals(message.type())) {
            return;
        }
        Object approve = message.payload().get("approve");
        boolean allowed = approve instanceof Boolean bool
                ? bool
                : approve != null && Boolean.parseBoolean(String.valueOf(approve).toLowerCase(Locale.ROOT));
        if (allowed) {
            agent.setPermissionMode(Mode.DEFAULT);
            lines.add("  note: Lead 已批准计划，权限模式已切到 default。");
        } else {
            Object feedback = message.payload().get("feedback");
            lines.add("  note: Lead 驳回计划，请根据反馈重新提交。");
            if (feedback != null) {
                lines.add("  feedback: " + feedback);
            }
        }
    }
}
