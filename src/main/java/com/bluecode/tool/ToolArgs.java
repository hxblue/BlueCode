package com.bluecode.tool;

import java.util.Map;

final class ToolArgs {
    private ToolArgs() {
    }

    static String requiredString(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数: " + name);
        }
        return text;
    }

    static String optionalString(Map<String, Object> args, String name, String defaultValue) {
        Object value = args == null ? null : args.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("参数 " + name + " 必须是字符串");
        }
        return text.isBlank() ? defaultValue : text;
    }
}
