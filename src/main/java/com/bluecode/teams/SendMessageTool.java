package com.bluecode.teams;

import com.bluecode.team.BackendType;
import com.bluecode.team.Team;
import com.bluecode.team.TeamManager;
import com.bluecode.team.TeammateInfo;
import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;
import com.bluecode.tool.ToolContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SendMessageTool implements Tool {
    private final TeamManager manager;
    private final com.bluecode.task.Manager taskManager;

    public SendMessageTool(TeamManager manager, com.bluecode.task.Manager taskManager) {
        this.manager = manager;
        this.taskManager = taskManager;
    }

    @Override
    public String name() {
        return "SendMessage";
    }

    @Override
    public String description() {
        return "向 Team 队员发送结构化消息，支持按名称、agentId 或 * 广播。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String key : List.of("teamName", "to", "summary", "message", "type")) {
            properties.put(key, Map.of("type", "string"));
        }
        properties.put("payload", Map.of("type", "object"));
        return Map.of("type", "object", "properties", properties, "required", List.of("teamName", "to", "message"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            Team team = team(args);
            String to = TeamCreateTool.str(args, "to");
            List<TeammateInfo> targets = targets(team, to);
            Mailbox mailbox = new Mailbox(team.mailboxDir());
            MessageType type = TeamCreateTool.str(args, "type").isBlank()
                    ? MessageType.TEXT
                    : MessageType.fromWire(TeamCreateTool.str(args, "type"));
            List<String> delivered = new ArrayList<>();
            for (TeammateInfo target : targets) {
                Message msg = new Message(
                        "lead",
                        target.agentId(),
                        type,
                        TeamCreateTool.str(args, "summary"),
                        TeamCreateTool.str(args, "message"),
                        payload(args.get("payload")),
                        0,
                        false);
                mailbox.write(target.agentId(), msg);
                BackendFactory.create(target.backendType(), manager.backendDeps()).wake(target.paneId(), target.agentId());
                resumeIfIdle(target, msg.content());
                delivered.add(target.agentId());
            }
            return Result.ok(Json.write(Map.of("deliveredTo", delivered, "teamName", team.name())));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private Team team(Map<String, Object> args) {
        String teamName = TeamCreateTool.str(args, "teamName");
        return manager.get(teamName).orElseThrow(() -> new IllegalArgumentException("找不到 Team: " + teamName));
    }

    private List<TeammateInfo> targets(Team team, String to) {
        if ("*".equals(to)) {
            return team.members().stream().filter(member -> !"lead".equals(member.name())).toList();
        }
        return team.memberByName(to)
                .or(() -> team.memberByAgentId(to))
                .or(() -> manager.registry().resolve(to).flatMap(team::memberByAgentId))
                .map(List::of)
                .orElseThrow(() -> new IllegalArgumentException("找不到收件人: " + to));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    private void resumeIfIdle(TeammateInfo target, String content) {
        if (target.backendType() != BackendType.IN_PROCESS || taskManager == null) {
            return;
        }
        taskManager.get(target.agentId()).ifPresent(task -> {
            if (task.status() == com.bluecode.task.Status.COMPLETED) {
                taskManager.sendMessage(target.name(), content);
            }
        });
    }
}
