package com.bluecode.task;

import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;

import java.util.List;
import java.util.Map;

public final class TaskGetTool implements Tool {
    private final Manager manager;

    public TaskGetTool(Manager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TaskGet";
    }

    @Override
    public String description() {
        return "按 task_id 查看后台子 Agent 任务详情。";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("task_id", Map.of("type", "string")),
                "required", List.of("task_id"));
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
        String id = String.valueOf(args.getOrDefault("task_id", "")).trim();
        if (id.isBlank()) {
            return Result.error("TaskGet 需要 task_id");
        }
        return manager.get(id)
                .map(task -> Result.ok(TaskJson.write(TaskJson.detail(task))))
                .orElseGet(() -> Result.error("未知 task_id: " + id));
    }
}
