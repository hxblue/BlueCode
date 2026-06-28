package com.bluecode.task;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

final class TaskJson {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private TaskJson() {
    }

    static Map<String, Object> summary(BackgroundTask task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", task.id());
        item.put("name", task.name());
        item.put("status", status(task));
        item.put("tool_count", task.toolCount());
        item.put("last_activity", task.lastActivity());
        return item;
    }

    static Map<String, Object> detail(BackgroundTask task) {
        Map<String, Object> item = summary(task);
        item.put("task", task.task());
        item.put("result", task.result());
        item.put("err", task.err() == null ? "" : task.err().getMessage());
        item.put("start_time", task.startTime().toString());
        item.put("end_time", task.endTime() == null ? "" : task.endTime().toString());
        item.put("usage", Map.of(
                "input", task.usage().input(),
                "output", task.usage().output(),
                "cache_write", task.usage().cacheWrite(),
                "cache_read", task.usage().cacheRead()));
        return item;
    }

    static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"json encode failed\"}";
        }
    }

    static String status(BackgroundTask task) {
        return task.status().name().toLowerCase();
    }
}
