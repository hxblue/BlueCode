package com.bluecode.tool;

import java.util.Map;

public interface Tool {
    String name();

    String description();

    Map<String, Object> schema();

    // true 表示只读工具，可在 Plan Mode 放行，也可被 Agent 并发执行。
    boolean readOnly();

    default boolean isSystem() {
        return false;
    }

    default Result execute(Map<String, Object> args) {
        return execute(ToolContext.root(), args);
    }

    default Result execute(ToolContext ctx, Map<String, Object> args) {
        return execute(args);
    }
}
