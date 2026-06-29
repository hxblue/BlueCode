package com.bluecode.cli;

import com.bluecode.team.Team;
import com.bluecode.team.TeamManager;
import com.bluecode.teams.Mailbox;
import com.bluecode.teams.Message;
import com.bluecode.teams.MessageType;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TeamMemberRunner {
    private TeamMemberRunner() {
    }

    public static boolean isTeamMember(String[] args) {
        for (String arg : args == null ? new String[0] : args) {
            if ("--team-member".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    public static Args parse(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean teamMember = false;
        for (int i = 0; i < (args == null ? 0 : args.length); i++) {
            String arg = args[i];
            if ("--team-member".equals(arg)) {
                teamMember = true;
                continue;
            }
            if (arg.startsWith("--") && i + 1 < args.length && !args[i + 1].startsWith("--")) {
                values.put(arg.substring(2), args[++i]);
            } else if (arg.startsWith("--")) {
                values.put(arg.substring(2), "true");
            }
        }
        return new Args(
                teamMember,
                values.getOrDefault("team", ""),
                values.getOrDefault("member", ""),
                values.getOrDefault("agent-id", ""),
                values.getOrDefault("session-dir", ""),
                values.getOrDefault("worktree", ""),
                values.getOrDefault("agent-type", ""),
                values.getOrDefault("model", ""),
                Boolean.parseBoolean(values.getOrDefault("plan-mode", "false")));
    }

    public static void run(Context context, Args args) throws IOException {
        if (context == null || context.teamManager() == null) {
            throw new IOException("TeamMemberRunner 缺少 TeamManager");
        }
        Team team = context.teamManager().get(args.team()).orElseThrow(() -> new IOException("找不到 Team: " + args.team()));
        System.out.printf("[team-member] %s · team=%s · agent=%s · cwd=%s%n",
                args.member(), args.team(), args.agentId(), args.worktree());
        Mailbox mailbox = new Mailbox(team.mailboxDir());
        var unread = mailbox.readUnread(args.agentId());
        if (!unread.messages().isEmpty()) {
            for (Message message : unread.messages()) {
                System.out.printf("[message] from=%s type=%s summary=%s%n",
                        message.from(), message.type().wireValue(), message.summary());
                if (!message.content().isBlank()) {
                    System.out.println(message.content());
                }
            }
            mailbox.markRead(args.agentId(), unread.indices());
        }
        team.setMemberActive(args.member(), false);
        mailbox.write(team.leadAgentId(), new Message(
                args.member(),
                team.leadAgentId(),
                MessageType.IDLE_NOTIFICATION,
                args.member() + " idle",
                "队员子进程已完成当前轮询。",
                Map.of("agentId", args.agentId()),
                0,
                false));
    }

    public record Context(TeamManager teamManager) {
    }

    public record Args(
            boolean teamMember,
            String team,
            String member,
            String agentId,
            String sessionDir,
            String worktree,
            String agentType,
            String model,
            boolean planMode) {
    }
}
