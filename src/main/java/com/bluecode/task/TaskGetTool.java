package com.bluecode.task;

import com.bluecode.team.Team;
import com.bluecode.team.TeamManager;
import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TaskGetTool implements Tool {
    private final Manager manager;
    private final TeamManager teamManager;

    public TaskGetTool(Manager manager) {
        this(manager, null);
    }

    public TaskGetTool(Manager manager, TeamManager teamManager) {
        this.manager = manager;
        this.teamManager = teamManager;
    }

    @Override
    public String name() {
        return "TaskGet";
    }

    @Override
    public String description() {
        return "按 task_id 查看后台子 Agent 任务详情；传 teamName/taskId 时查看 Team 共享任务详情。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("task_id", Map.of("type", "string", "description", "后台任务 ID"));
        properties.put("teamName", Map.of("type", "string", "description", "Team 名称"));
        properties.put("taskId", Map.of("type", "string", "description", "Team 共享任务 ID"));
        return Map.of(
                "type", "object",
                "properties", properties,
                "anyOf", List.of(
                        Map.of("required", List.of("task_id")),
                        Map.of("required", List.of("teamName", "taskId"))));
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
        if (hasTeamArgs(args)) {
            return executeTeam(args);
        }
        String id = string(args, "task_id");
        if (id.isBlank()) {
            return Result.error("TaskGet 需要 task_id，或 teamName + taskId");
        }
        return manager.get(id)
                .map(task -> Result.ok(TaskJson.write(TaskJson.detail(task))))
                .orElseGet(() -> Result.error("未知 task_id: " + id));
    }

    private Result executeTeam(Map<String, Object> args) {
        if (teamManager == null) {
            return Result.error("TaskGet 团队参数需要 TeamManager");
        }
        String teamName = string(args, "teamName");
        String taskId = string(args, "taskId");
        if (teamName.isBlank() || taskId.isBlank()) {
            return Result.error("TaskGet 需要 task_id，或 teamName + taskId");
        }
        try {
            Team team = teamManager.get(teamName)
                    .orElseThrow(() -> new IllegalArgumentException("找不到 Team: " + teamName));
            return new com.bluecode.teams.Store(team.tasksPath()).get(taskId)
                    .map(task -> Result.ok(TaskJson.write(task)))
                    .orElseGet(() -> Result.error("找不到任务"));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private boolean hasTeamArgs(Map<String, Object> args) {
        return !string(args, "teamName").isBlank() || !string(args, "taskId").isBlank();
    }

    private String string(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
