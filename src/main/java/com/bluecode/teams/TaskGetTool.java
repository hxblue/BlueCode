package com.bluecode.teams;

import com.bluecode.team.Team;
import com.bluecode.team.TeamManager;
import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;
import com.bluecode.tool.ToolContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TaskGetTool implements Tool {
    private final TeamManager manager;

    public TaskGetTool(TeamManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TaskGet";
    }

    @Override
    public String description() {
        return "读取指定 Team 共享任务详情。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("teamName", Map.of("type", "string", "description", "团队名称"));
        properties.put("taskId", Map.of("type", "string", "description", "任务 ID"));
        return Map.of("type", "object", "properties", properties, "required", List.of("teamName", "taskId"));
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            Team team = team(args);
            return new Store(team.tasksPath()).get(TeamCreateTool.str(args, "taskId"))
                    .map(task -> Result.ok(Json.write(task)))
                    .orElseGet(() -> Result.error("找不到任务"));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    private Team team(Map<String, Object> args) {
        String teamName = TeamCreateTool.str(args, "teamName");
        return manager.get(teamName).orElseThrow(() -> new IllegalArgumentException("找不到 Team: " + teamName));
    }
}
