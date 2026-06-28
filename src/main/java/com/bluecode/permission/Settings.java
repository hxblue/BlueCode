package com.bluecode.permission;

import com.bluecode.llm.ToolCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record Settings(String defaultMode, List<String> allow, List<String> deny) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Settings {
        allow = List.copyOf(allow == null ? List.of() : allow);
        deny = List.copyOf(deny == null ? List.of() : deny);
    }

    public static Settings empty() {
        return new Settings(null, List.of(), List.of());
    }

    static Settings loadSettings(Path path) {
        if (path == null || !Files.exists(path)) {
            return empty();
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            Object loaded = new Yaml().load(reader);
            if (!(loaded instanceof Map<?, ?> map)) {
                return empty();
            }
            String defaultMode = asString(map.get("defaultMode"));
            Object permissionsObj = map.get("permissions");
            Map<?, ?> permissions = permissionsObj instanceof Map<?, ?> p ? p : Map.of();
            Object allowValue = permissions.containsKey("allow") ? permissions.get("allow") : map.get("allow");
            Object denyValue = permissions.containsKey("deny") ? permissions.get("deny") : map.get("deny");
            List<String> allow = readList(allowValue);
            List<String> deny = readList(denyValue);
            return new Settings(defaultMode, allow, deny);
        } catch (IOException | RuntimeException e) {
            return empty();
        }
    }

    static RuleSet toRuleSet(Settings settings) {
        List<Rule> allowRules = parseRules(settings.allow(), true);
        List<Rule> denyRules = parseRules(settings.deny(), false);
        return new RuleSet(allowRules, denyRules);
    }

    private static List<Rule> parseRules(List<String> rules, boolean allow) {
        List<Rule> parsed = new ArrayList<>();
        for (String rule : rules == null ? List.<String>of() : rules) {
            try {
                parsed.add(Rule.parseOrThrow(rule, allow));
            } catch (Rule.RuleParseException e) {
                System.err.printf("rule \"%s\" parse failed: %s%n", rule, e.getMessage());
            }
        }
        return List.copyOf(parsed);
    }

    static String friendlyName(String internal) {
        if (internal == null) {
            return "";
        }
        return switch (internal.strip().toLowerCase(Locale.ROOT)) {
            case "bash" -> "Bash";
            case "readfile", "read_file" -> "Read";
            case "writefile", "write_file" -> "Write";
            case "editfile", "edit_file" -> "Edit";
            case "glob" -> "Glob";
            case "grep" -> "Grep";
            default -> internal;
        };
    }

    static Category categorize(String internal, boolean readOnly) {
        if (readOnly) {
            return Category.READ;
        }
        String name = internal == null ? "" : internal.strip().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "readfile", "read_file", "glob", "grep" -> Category.READ;
            case "writefile", "write_file", "editfile", "edit_file" -> Category.WRITE;
            default -> Category.EXEC;
        };
    }

    static TargetInfo extractTarget(ToolCall call) {
        if (call == null) {
            return new TargetInfo("", false, false);
        }
        String name = call.name().strip().toLowerCase(Locale.ROOT);
        JsonNode root;
        try {
            root = MAPPER.readTree(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments());
        } catch (Exception e) {
            return switch (name) {
                case "readfile", "read_file", "writefile", "write_file", "editfile", "edit_file", "glob", "grep" ->
                        new TargetInfo("", true, false);
                default -> new TargetInfo("", false, false);
            };
        }

        return switch (name) {
            case "readfile", "read_file", "writefile", "write_file", "editfile", "edit_file" ->
                    targetFrom(root, "path", true, null);
            case "glob", "grep" -> targetFrom(root, "path", true, ".");
            case "bash" -> targetFrom(root, "command", false, null);
            default -> new TargetInfo("", false, false);
        };
    }

    private static TargetInfo targetFrom(JsonNode root, String key, boolean isFile, String fallback) {
        JsonNode value = root.path(key);
        if (value.isTextual() && !value.asText().isBlank()) {
            return new TargetInfo(value.asText(), isFile, true);
        }
        if (fallback != null) {
            return new TargetInfo(fallback, isFile, true);
        }
        return new TargetInfo("", isFile, false);
    }

    private static String asString(Object value) {
        return value instanceof String text && !text.isBlank() ? text.strip() : null;
    }

    private static List<String> readList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) {
                result.add(text.strip());
            }
        }
        return List.copyOf(result);
    }

    public record TargetInfo(String target, boolean isFile, boolean ok) {
    }

}
