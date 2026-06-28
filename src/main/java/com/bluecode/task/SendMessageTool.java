package com.bluecode.task;

import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;

import java.util.List;
import java.util.Map;

public final class SendMessageTool implements Tool {
    private final Manager manager;

    public SendMessageTool(Manager manager) {
        this.manager = manager;
    }

    @Override
    public String name() {
        return "SendMessage";
    }

    @Override
    public String description() {
        return "给已完成且仍保留上下文的后台子 Agent 续派一条消息。";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "message", Map.of("type", "string")),
                "required", List.of("name", "message"));
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
        String name = String.valueOf(args.getOrDefault("name", "")).trim();
        String message = String.valueOf(args.getOrDefault("message", "")).trim();
        if (name.isBlank() || message.isBlank()) {
            return Result.error("SendMessage 需要 name 和 message");
        }
        try {
            String id = manager.sendMessage(name, message);
            return Result.ok("{\"task_id\":\"" + id + "\",\"status\":\"resumed\"}");
        } catch (Exception e) {
            return Result.error(e.getMessage());
        }
    }
}
