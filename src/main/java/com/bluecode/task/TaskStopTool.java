package com.bluecode.task;

import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;

import java.util.List;
import java.util.Map;

public final class TaskStopTool implements Tool {
    private final Manager manager;

    public TaskStopTool(Manager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TaskStop";
    }

    @Override
    public String description() {
        return "请求取消一个后台子 Agent 任务。";
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
        return false;
    }

    @Override
    public boolean isSystem() {
        return true;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        String id = String.valueOf(args.getOrDefault("task_id", "")).trim();
        if (id.isBlank()) {
            return Result.error("TaskStop 需要 task_id");
        }
        if (!manager.stop(id)) {
            return Result.error("未知 task_id: " + id);
        }
        return Result.ok("{\"status\":\"cancellation_requested\"}");
    }
}
