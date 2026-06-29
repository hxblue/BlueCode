package com.bluecode.task;

import com.bluecode.team.BackendType;
import com.bluecode.team.Team;
import com.bluecode.team.TeamManager;
import com.bluecode.team.TeammateInfo;
import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SendMessageTool implements Tool {
    private final Manager manager;
    private final TeamManager teamManager;

    public SendMessageTool(Manager manager) {
        this(manager, null);
    }

    public SendMessageTool(Manager manager, TeamManager teamManager) {
        this.manager = manager;
        this.teamManager = teamManager;
    }

    @Override
    public String name() {
        return "SendMessage";
    }

    @Override
    public String description() {
        return "给已完成的后台子 Agent 续派消息；传 teamName/to 时向 Team 队员发送结构化消息。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", Map.of("type", "string", "description", "后台 Agent 名称"));
        properties.put("teamName", Map.of("type", "string", "description", "Team 名称"));
        properties.put("to", Map.of("type", "string", "description", "队员名称、agentId 或 *"));
        properties.put("summary", Map.of("type", "string", "description", "消息摘要"));
        properties.put("message", Map.of("type", "string", "description", "消息正文"));
        properties.put("type", Map.of("type", "string", "description", "text/shutdown_request/shutdown_response/plan_approval_response"));
        properties.put("payload", Map.of("type", "object"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "anyOf", List.of(
                        Map.of("required", List.of("name", "message")),
                        Map.of("required", List.of("teamName", "to", "message"))));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public boolean isSystem() {
        return true;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        if (hasTeamArgs(args)) {
            return executeTeam(args);
        }
        String name = string(args, "name");
        String message = string(args, "message");
        if (name.isBlank() || message.isBlank()) {
            return Result.error("SendMessage 需要 name + message，或 teamName + to + message");
        }
        try {
            String id = manager.sendMessage(name, message);
            return Result.ok("{\"task_id\":\"" + id + "\",\"status\":\"resumed\"}");
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private Result executeTeam(Map<String, Object> args) {
        if (teamManager == null) {
            return Result.error("SendMessage 团队参数需要 TeamManager");
        }
        try {
            Team team = team(args);
            String to = string(args, "to");
            if (to.isBlank()) {
                to = string(args, "name");
            }
            String message = string(args, "message");
            if (to.isBlank() || message.isBlank()) {
                return Result.error("SendMessage 需要 teamName + to + message");
            }
            List<TeammateInfo> targets = targets(team, to);
            com.bluecode.teams.Mailbox mailbox = new com.bluecode.teams.Mailbox(team.mailboxDir());
            com.bluecode.teams.MessageType type = string(args, "type").isBlank()
                    ? com.bluecode.teams.MessageType.TEXT
                    : com.bluecode.teams.MessageType.fromWire(string(args, "type"));
            List<String> delivered = new ArrayList<>();
            for (TeammateInfo target : targets) {
                com.bluecode.teams.Message msg = new com.bluecode.teams.Message(
                        "lead",
                        target.agentId(),
                        type,
                        string(args, "summary"),
                        message,
                        payload(args.get("payload")),
                        0,
                        false);
                mailbox.write(target.agentId(), msg);
                com.bluecode.teams.BackendFactory
                        .create(target.backendType(), teamManager.backendDeps())
                        .wake(target.paneId(), target.agentId());
                resumeIfIdle(target, msg.content());
                delivered.add(target.agentId());
            }
            return Result.ok(TaskJson.write(Map.of("deliveredTo", delivered, "teamName", team.name())));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private Team team(Map<String, Object> args) {
        String teamName = string(args, "teamName");
        return teamManager.get(teamName)
                .orElseThrow(() -> new IllegalArgumentException("找不到 Team: " + teamName));
    }

    private List<TeammateInfo> targets(Team team, String to) {
        if ("*".equals(to)) {
            return team.members().stream().filter(member -> !"lead".equals(member.name())).toList();
        }
        return team.memberByName(to)
                .or(() -> team.memberByAgentId(to))
                .or(() -> teamManager.registry().resolve(to).flatMap(team::memberByAgentId))
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
        if (target.backendType() != BackendType.IN_PROCESS || manager == null) {
            return;
        }
        manager.get(target.agentId()).ifPresent(task -> {
            if (task.status() == Status.COMPLETED) {
                manager.sendMessage(target.name(), content);
            }
        });
    }

    private boolean hasTeamArgs(Map<String, Object> args) {
        return !string(args, "teamName").isBlank() || !string(args, "to").isBlank();
    }

    private String string(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
