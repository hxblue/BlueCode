package com.bluecode.mcp;

import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfigLoader {
    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private ConfigLoader() {
    }

    public static McpConfig loadConfig(Path root) {
        try {
            Path projectRoot = root == null ? Path.of("").toAbsolutePath() : root.toAbsolutePath().normalize();
            Path userPath = Path.of(System.getProperty("user.home", ".")).resolve(".bluecode/config.yaml");
            Path projectPath = projectRoot.resolve(".bluecode.yaml");

            Map<String, RawServer> user = loadFile(userPath);
            Map<String, RawServer> project = loadFile(projectPath);
            Map<String, RawServer> merged = mergeServers(expandLayer(user), expandLayer(project));
            Map<String, ServerConfig> valid = new LinkedHashMap<>();
            for (Map.Entry<String, RawServer> entry : merged.entrySet()) {
                validateServer(entry.getKey(), entry.getValue()).ifPresent(cfg -> valid.put(entry.getKey(), cfg));
            }
            return new McpConfig(valid);
        } catch (RuntimeException e) {
            System.err.println("[mcp] warn: load config failed: " + e.getMessage());
            return McpConfig.empty();
        }
    }

    static Map<String, RawServer> loadFile(Path path) {
        if (path == null || !Files.exists(path)) {
            return Map.of();
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            Object loaded = new Yaml().load(reader);
            if (!(loaded instanceof Map<?, ?> root)) {
                return Map.of();
            }
            Object mcpServers = root.get("mcp_servers");
            if (mcpServers == null) {
                return Map.of();
            }
            if (!(mcpServers instanceof Map<?, ?> servers)) {
                System.err.println("[mcp] warn: skip config file " + path + ": mcp_servers must be a map");
                return Map.of();
            }
            Map<String, RawServer> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : servers.entrySet()) {
                if (!(entry.getKey() instanceof String name) || name.isBlank()) {
                    System.err.println("[mcp] warn: skip unnamed server in " + path);
                    continue;
                }
                if (!(entry.getValue() instanceof Map<?, ?> serverMap)) {
                    System.err.println("[mcp] warn: skip server " + name + ": definition must be a map");
                    continue;
                }
                bindServer(name, serverMap).ifPresent(raw -> result.put(name, raw));
            }
            return result;
        } catch (RuntimeException | java.io.IOException e) {
            System.err.println("[mcp] warn: skip config file " + path + ": " + e.getMessage());
            return Map.of();
        }
    }

    static Expansion expandVars(String value) {
        if (value == null || value.isEmpty()) {
            return new Expansion(value == null ? "" : value, List.of());
        }
        Matcher matcher = ENV_VAR.matcher(value);
        StringBuilder out = new StringBuilder();
        List<String> undefined = new ArrayList<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            String replacement = System.getenv(name);
            if (replacement == null) {
                undefined.add(name);
                replacement = "";
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return new Expansion(out.toString(), undefined);
    }

    static RawServer applyExpansion(String name, RawServer server) {
        Map<String, String> env = expandMapValues(name, server.env());
        Map<String, String> headers = expandMapValues(name, server.headers());
        return new RawServer(server.type(), server.command(), server.args(), env, server.url(), headers);
    }

    static Map<String, RawServer> mergeServers(Map<String, RawServer> user, Map<String, RawServer> project) {
        Map<String, RawServer> merged = new LinkedHashMap<>();
        if (user != null) {
            merged.putAll(user);
        }
        if (project != null) {
            merged.putAll(project);
        }
        return merged;
    }

    static Optional<ServerConfig> validateServer(String name, RawServer server) {
        if (server == null || isBlank(server.type())) {
            return skipServer(name, "missing type");
        }
        return switch (server.type()) {
            case "stdio" -> {
                if (isBlank(server.command())) {
                    yield skipServer(name, "stdio server missing command");
                }
                yield Optional.of(new ServerConfig("stdio", server.command(), server.args(), server.env(), null, Map.of()));
            }
            case "http" -> {
                if (isBlank(server.url())) {
                    yield skipServer(name, "http server missing url");
                }
                yield Optional.of(new ServerConfig("http", null, List.of(), Map.of(), server.url(), server.headers()));
            }
            default -> skipServer(name, "unsupported type " + server.type());
        };
    }

    private static Map<String, RawServer> expandLayer(Map<String, RawServer> layer) {
        Map<String, RawServer> expanded = new LinkedHashMap<>();
        for (Map.Entry<String, RawServer> entry : layer.entrySet()) {
            expanded.put(entry.getKey(), applyExpansion(entry.getKey(), entry.getValue()));
        }
        return expanded;
    }

    private static Map<String, String> expandMapValues(String serverName, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> expanded = new LinkedHashMap<>();
        Set<String> undefined = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Expansion expansion = expandVars(entry.getValue());
            expanded.put(entry.getKey(), expansion.out());
            undefined.addAll(expansion.undefined());
        }
        for (String name : undefined) {
            System.err.printf("[mcp] warn: undefined env var ${%s} referenced by server %s%n", name, serverName);
        }
        return expanded;
    }

    private static Optional<RawServer> bindServer(String name, Map<?, ?> map) {
        try {
            return Optional.of(new RawServer(
                    optionalString(map.get("type")),
                    optionalString(map.get("command")),
                    optionalStringList(map.get("args"), "args"),
                    optionalStringMap(map.get("env"), "env"),
                    optionalString(map.get("url")),
                    optionalStringMap(map.get("headers"), "headers")));
        } catch (IllegalArgumentException e) {
            System.err.println("[mcp] warn: skip server " + name + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    private static String optionalString(Object value) {
        return value instanceof String text ? text : null;
    }

    private static List<String> optionalStringList(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(field + " must be a string list");
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text)) {
                throw new IllegalArgumentException(field + " must be a string list");
            }
            out.add(text);
        }
        return List.copyOf(out);
    }

    private static Map<String, String> optionalStringMap(Object value, String field) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(field + " must be a string map");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String text)) {
                throw new IllegalArgumentException(field + " must be a string map");
            }
            out.put(key, text);
        }
        return Map.copyOf(out);
    }

    private static Optional<ServerConfig> skipServer(String name, String reason) {
        System.err.printf("[mcp] warn: skip server %s: %s%n", name, reason);
        return Optional.empty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record RawServer(
            String type,
            String command,
            List<String> args,
            Map<String, String> env,
            String url,
            Map<String, String> headers) {
        RawServer {
            args = List.copyOf(args == null ? List.of() : args);
            env = Map.copyOf(env == null ? Map.of() : env);
            headers = Map.copyOf(headers == null ? Map.of() : headers);
        }
    }

    record Expansion(String out, List<String> undefined) {
    }
}
