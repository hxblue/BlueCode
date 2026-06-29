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

public final class TaskListTool implements Tool {
    private final TeamManager manager;

    public TaskListTool(TeamManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TaskList";
    }

    @Override
    public String description() {
        return "列出指定 Team 的共享任务，可按状态过滤。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("teamName", Map.of("type", "string", "description", "团队名称"));
        properties.put("status", Map.of("type", "string", "description", "pending/in_progress/completed/blocked"));
        return Map.of("type", "object", "properties", properties, "required", List.of("teamName"));
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            Team team = team(args);
            String status = TeamCreateTool.str(args, "status");
            Optional<Status> filter = status.isBlank() ? Optional.empty() : Optional.of(Status.fromWire(status));
            return Result.ok(Json.write(new Store(team.tasksPath()).list(new Filter(filter))));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private Team team(Map<String, Object> args) {
        String teamName = TeamCreateTool.str(args, "teamName");
        return manager.get(teamName).orElseThrow(() -> new IllegalArgumentException("找不到 Team: " + teamName));
    }
}
