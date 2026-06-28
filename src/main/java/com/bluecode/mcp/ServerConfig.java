package com.bluecode.mcp;

import java.util.List;
import java.util.Map;

public record ServerConfig(
        String type,
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers) {
    public ServerConfig {
        args = List.copyOf(args == null ? List.of() : args);
        env = Map.copyOf(env == null ? Map.of() : env);
        headers = Map.copyOf(headers == null ? Map.of() : headers);
    }
}
