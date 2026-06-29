package com.bluecode.teams;

import com.bluecode.team.TeamManager;
import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;
import com.bluecode.tool.ToolContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TeamDeleteTool implements Tool {
    private final TeamManager manager;

    public TeamDeleteTool(TeamManager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TeamDelete";
    }

    @Override
    public String description() {
        return "删除一个 Agent Team；默认拒绝删除仍有活跃队员的团队。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("teamName", Map.of("type", "string", "description", "团队名称"));
        properties.put("force", Map.of("type", "boolean", "description", "是否强制删除"));
        return Map.of("type", "object", "properties", properties, "required", List.of("teamName"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            String teamName = TeamCreateTool.str(args, "teamName");
            manager.delete(teamName, bool(args, "force"));
            return Result.ok(Json.write(Map.of("deleted", true, "teamName", teamName)));
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }

    static boolean bool(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }
}
