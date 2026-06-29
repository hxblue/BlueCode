package com.bluecode.teams;

import com.bluecode.team.Team;
import com.bluecode.team.TeamManager;
import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;
import com.bluecode.tool.ToolContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TaskCreateTool implements Tool {
    private final TeamManager manager;

    public TaskCreateTool(TeamManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TaskCreate";
    }

    @Override
    public String description() {
        return "在指定 Team 的共享任务列表中创建任务。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("teamName", Map.of("type", "string", "description", "团队名称"));
        properties.put("title", Map.of("type", "string", "description", "任务标题"));
        properties.put("description", Map.of("type", "string", "description", "任务详情"));
        properties.put("assignee", Map.of("type", "string", "description", "负责人名称"));
        properties.put("blockedBy", Map.of("type", "array", "items", Map.of("type", "string")));
        return Map.of("type", "object", "properties", properties, "required", List.of("teamName", "title"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            Team team = team(args);
            Store store = new Store(team.tasksPath());
            String id = store.create(new Task(
                    "",
                    TeamCreateTool.str(args, "title"),
                    TeamCreateTool.str(args, "description"),
                    Status.PENDING,
                    TeamCreateTool.str(args, "assignee"),
                    strings(args.get("blockedBy")),
                    List.of(),
                    0,
                    0));
            return Result.ok(Json.write(Map.of("taskId", id, "teamName", team.name())));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    Team team(Map<String, Object> args) {
        String teamName = TeamCreateTool.str(args, "teamName");
        return manager.get(teamName).orElseThrow(() -> new IllegalArgumentException("找不到 Team: " + teamName));
    }

    static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
    }
}
