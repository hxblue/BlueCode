package com.bluecode.task;

import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;

import java.util.List;
import java.util.Map;

public final class TaskListTool implements Tool {
    private final Manager manager;

    public TaskListTool(Manager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "TaskList";
    }

    @Override
    public String description() {
        return "列出当前内存中的后台子 Agent 任务。";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of("type", "object", "properties", Map.of());
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
        List<Map<String, Object>> items = manager.list().stream()
                .map(TaskJson::summary)
                .toList();
        return Result.ok(TaskJson.write(items));
    }
}
