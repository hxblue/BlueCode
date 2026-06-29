package com.bluecode.teams;

import com.bluecode.team.Team;
import com.bluecode.team.TeamManager;
import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;
import com.bluecode.tool.ToolContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class TaskUpdateTool implements Tool {
    private final TeamManager manager;

    public TaskUpdateTool(TeamManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TaskUpdate";
    }

    @Override
    public String description() {
        return "更新 Team 共享任务字段和依赖关系。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String key : List.of("teamName", "taskId", "title", "description", "status", "assignee")) {
            properties.put(key, Map.of("type", "string"));
        }
        for (String key : List.of("addBlocks", "addBlockedBy", "removeBlocks", "removeBlockedBy")) {
            properties.put(key, Map.of("type", "array", "items", Map.of("type", "string")));
        }
        return Map.of("type", "object", "properties", properties, "required", List.of("teamName", "taskId"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            Team team = team(args);
            String status = TeamCreateTool.str(args, "status");
            Patch patch = new Patch(
                    opt(args, "title"),
                    opt(args, "description"),
                    status.isBlank() ? Optional.empty() : Optional.of(Status.fromWire(status)),
                    opt(args, "assignee"),
                    TaskCreateTool.strings(args.get("addBlocks")),
                    TaskCreateTool.strings(args.get("addBlockedBy")),
                    TaskCreateTool.strings(args.get("removeBlocks")),
                    TaskCreateTool.strings(args.get("removeBlockedBy")));
            new Store(team.tasksPath()).update(TeamCreateTool.str(args, "taskId"), patch);
            return Result.ok(Json.write(Map.of("updated", true, "taskId", TeamCreateTool.str(args, "taskId"))));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private Optional<String> opt(Map<String, Object> args, String key) {
        String value = TeamCreateTool.str(args, key);
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private Team team(Map<String, Object> args) {
        String teamName = TeamCreateTool.str(args, "teamName");
        return manager.get(teamName).orElseThrow(() -> new IllegalArgumentException("找不到 Team: " + teamName));
    }
}
