package com.bluecode.task;

import com.bluecode.team.Team;
import com.bluecode.team.TeamManager;
import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TaskListTool implements Tool {
    private final Manager manager;
    private final TeamManager teamManager;

    public TaskListTool(Manager manager) {
        this(manager, null);
    }

    public TaskListTool(Manager manager, TeamManager teamManager) {
        this.manager = manager;
        this.teamManager = teamManager;
    }

    @Override
    public String name() {
        return "TaskList";
    }

    @Override
    public String description() {
        return "列出后台子 Agent 任务；传 teamName 时列出 Team 共享任务。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("teamName", Map.of("type", "string", "description", "Team 名称"));
        properties.put("status", Map.of("type", "string", "description", "pending/in_progress/completed/blocked"));
        return Map.of("type", "object", "properties", properties);
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public boolean isSystem() {
        return true;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        String teamName = string(args, "teamName");
        if (!teamName.isBlank()) {
            return executeTeam(args, teamName);
        }
        List<Map<String, Object>> items = manager.list().stream()
                .map(TaskJson::summary)
                .toList();
        return Result.ok(TaskJson.write(items));
    }

    private Result executeTeam(Map<String, Object> args, String teamName) {
        if (teamManager == null) {
            return Result.error("TaskList 团队参数需要 TeamManager");
        }
        try {
            Team team = teamManager.get(teamName)
                    .orElseThrow(() -> new IllegalArgumentException("找不到 Team: " + teamName));
            String status = string(args, "status");
            Optional<com.bluecode.teams.Status> filter = status.isBlank()
                    ? Optional.empty()
                    : Optional.of(com.bluecode.teams.Status.fromWire(status));
            return Result.ok(TaskJson.write(
                    new com.bluecode.teams.Store(team.tasksPath()).list(new com.bluecode.teams.Filter(filter))));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private String string(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
