package com.bluecode.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record McpConfig(Map<String, ServerConfig> servers) {
    public McpConfig {
        servers = Collections.unmodifiableMap(servers == null ? Map.of() : new LinkedHashMap<>(servers));
    }

    public static McpConfig empty() {
        return new McpConfig(Map.of());
    }
}
