package com.bluecode.teams;

import com.bluecode.team.Team;
import com.bluecode.team.TeamManager;
import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;
import com.bluecode.tool.ToolContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TeamCreateTool implements Tool {
    private final TeamManager manager;

    public TeamCreateTool(TeamManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TeamCreate";
    }

    @Override
    public String description() {
        return "创建一个 Agent Team，并持久化团队配置。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("teamName", Map.of("type", "string", "description", "团队名称"));
        properties.put("description", Map.of("type", "string", "description", "团队描述"));
        return Map.of("type", "object", "properties", properties, "required", List.of("teamName"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            Team team = manager.create(str(args, "teamName"), str(args, "description"));
            return Result.ok(Json.write(Map.of(
                    "teamName", team.name(),
                    "sanitizedName", team.sanitizedName(),
                    "backend", team.backend().wireValue(),
                    "configPath", team.configPath().toString())));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    static String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
