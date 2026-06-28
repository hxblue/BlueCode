package com.bluecode.hook;

import com.bluecode.permission.ExactMatcher;
import com.bluecode.permission.GlobMatcher;
import com.bluecode.permission.Matcher;
import com.bluecode.permission.Matchers;
import com.bluecode.permission.NotMatcher;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class HookLoader {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern DURATION = Pattern.compile("^(\\d+)(ms|s|m)$", Pattern.CASE_INSENSITIVE);

    private HookLoader() {
    }

    public static HookEngine load(Path projectRoot) {
        Path root = projectRoot == null ? Path.of("").toAbsolutePath().normalize() : projectRoot.toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                root.resolve(".bluecode").resolve("hooks.yaml"),
                Path.of(System.getProperty("user.home", ".")).resolve(".bluecode").resolve("hooks.yaml")
        );
        List<HookRule> rules = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Path source : candidates) {
            if (!Files.exists(source)) {
                continue;
            }
            loadFile(source, rules, sources, names);
        }
        return new HookEngine(rules, sources, new HookExecutor());
    }

    private static void loadFile(Path source, List<HookRule> rules, List<String> sources, Set<String> names) {
        Object loaded;
        try (Reader reader = Files.newBufferedReader(source)) {
            loaded = new Yaml().load(reader);
        } catch (Exception e) {
            System.err.printf("hooks file \"%s\" parse failed: %s%n", source, e.getMessage());
            return;
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            System.err.printf("hooks file \"%s\" has invalid top-level structure, skipped%n", source);
            return;
        }
        Object hooks = root.get("hooks");
        if (hooks == null) {
            sources.add(source.toString());
            return;
        }
        if (!(hooks instanceof List<?> list)) {
            System.err.printf("hooks file \"%s\" field \"hooks\" must be a list, skipped%n", source);
            return;
        }
        sources.add(source.toString());
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                System.err.printf("hook \"%s\": hook entry must be an object, skipped%n", fallbackName(source, i));
                continue;
            }
            String name = string(map.get("name"));
            try {
                HookRule rule = compileRule(source, i, map);
                if (names.contains(rule.name())) {
                    System.err.printf("hook \"%s\": duplicate name from %s, skipped%n", rule.name(), source);
                    continue;
                }
                names.add(rule.name());
                rules.add(rule);
            } catch (HookCompileException e) {
                System.err.printf("hook \"%s\": %s, skipped%n", name.isBlank() ? fallbackName(source, i) : name, e.getMessage());
            }
        }
    }

    private static HookRule compileRule(Path source, int index, Map<?, ?> raw) throws HookCompileException {
        String name = requiredString(raw, "name");
        String eventText = requiredString(raw, "event");
        Event event = Event.parse(eventText)
                .orElseThrow(() -> new HookCompileException("unknown event \"%s\"".formatted(eventText)));
        boolean async = bool(raw.get("async"), false);
        if (async && event.isBlocking()) {
            throw new HookCompileException("async not allowed for blocking events");
        }
        Condition condition = compileCondition(raw.get("if"));
        Action action = compileAction(raw.get("action"));
        boolean onlyOnce = bool(raw.get("only_once"), false);
        Duration timeout = parseDuration(raw.get("timeout"));
        return new HookRule(name, event, condition, action, onlyOnce, async, timeout, source.toString());
    }

    private static Condition compileCondition(Object value) throws HookCompileException {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new HookCompileException("if must be an object");
        }
        boolean hasAll = map.containsKey("all_of");
        boolean hasAny = map.containsKey("any_of");
        if (hasAll && hasAny) {
            throw new HookCompileException("if cannot contain both all_of and any_of");
        }
        if (!hasAll && !hasAny) {
            throw new HookCompileException("if must contain all_of or any_of");
        }
        Object atomsValue = hasAll ? map.get("all_of") : map.get("any_of");
        if (!(atomsValue instanceof List<?> atomList)) {
            throw new HookCompileException((hasAll ? "all_of" : "any_of") + " must be a list");
        }
        List<AtomCondition> atoms = new ArrayList<>();
        for (Object atomValue : atomList) {
            if (!(atomValue instanceof Map<?, ?> atom)) {
                throw new HookCompileException("condition atom must be an object");
            }
            String field = requiredString(atom, "field");
            Matcher matcher = compileMatcher(atom.get("match"));
            atoms.add(new AtomCondition(field, matcher));
        }
        return new Condition(hasAll ? CombineMode.ALL_OF : CombineMode.ANY_OF, atoms);
    }

    private static Matcher compileMatcher(Object value) throws HookCompileException {
        if (value instanceof String text) {
            try {
                return Matchers.compile(text, false);
            } catch (Matchers.MatcherCompileException e) {
                throw new HookCompileException(e.getMessage(), e);
            }
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new HookCompileException("match must be an object");
        }
        String type = requiredString(map, "type").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "exact" -> new ExactMatcher(requiredValue(map, "value"));
            case "glob" -> new GlobMatcher(requiredValue(map, "value"), false);
            case "regex" -> compilePrefixed("~" + requiredValue(map, "value"));
            case "not" -> {
                if (!map.containsKey("inner")) {
                    throw new HookCompileException("not matcher requires inner");
                }
                yield new NotMatcher(compileMatcher(map.get("inner")));
            }
            default -> throw new HookCompileException("unknown match type \"%s\"".formatted(type));
        };
    }

    private static Matcher compilePrefixed(String pattern) throws HookCompileException {
        try {
            return Matchers.compile(pattern, false);
        } catch (Matchers.MatcherCompileException e) {
            throw new HookCompileException(e.getMessage(), e);
        }
    }

    private static Action compileAction(Object value) throws HookCompileException {
        if (!(value instanceof Map<?, ?> map)) {
            throw new HookCompileException("action must be an object");
        }
        String type = requiredString(map, "type").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "shell" -> new Action.Shell(requiredString(map, "command"));
            case "prompt" -> new Action.Prompt(requiredString(map, "text"));
            case "http" -> new Action.Http(
                    requiredString(map, "url"),
                    string(map.get("method")).isBlank() ? "POST" : string(map.get("method")),
                    headers(map.get("headers")),
                    map.containsKey("body") ? string(map.get("body")) : null);
            case "subagent" -> new Action.Subagent(
                    requiredString(map, "agent_name"),
                    requiredString(map, "prompt"));
            default -> throw new HookCompileException("unknown action type \"%s\"".formatted(type));
        };
    }

    private static Map<String, String> headers(Object value) throws HookCompileException {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new HookCompileException("headers must be an object");
        }
        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            headers.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return Map.copyOf(headers);
    }

    private static Duration parseDuration(Object value) throws HookCompileException {
        if (value == null) {
            return DEFAULT_TIMEOUT;
        }
        if (value instanceof Number number) {
            return Duration.ofSeconds(number.longValue());
        }
        String text = string(value);
        if (text.isBlank()) {
            return DEFAULT_TIMEOUT;
        }
        java.util.regex.Matcher matcher = DURATION.matcher(text.strip());
        if (matcher.matches()) {
            long amount = Long.parseLong(matcher.group(1));
            return switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                case "ms" -> Duration.ofMillis(amount);
                case "m" -> Duration.ofMinutes(amount);
                default -> Duration.ofSeconds(amount);
            };
        }
        try {
            return Duration.parse(text);
        } catch (Exception e) {
            throw new HookCompileException("invalid timeout \"%s\"".formatted(text));
        }
    }

    private static String requiredString(Map<?, ?> map, String key) throws HookCompileException {
        String value = string(map.get(key));
        if (value.isBlank()) {
            throw new HookCompileException(key + " is required");
        }
        return value;
    }

    private static String requiredValue(Map<?, ?> map, String key) throws HookCompileException {
        if (!map.containsKey(key)) {
            throw new HookCompileException(key + " is required");
        }
        return string(map.get(key));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static boolean bool(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static String fallbackName(Path source, int index) {
        return "<unnamed@" + source.getFileName() + "#" + index + ">";
    }

    private static final class HookCompileException extends Exception {
        private HookCompileException(String message) {
            super(message);
        }

        private HookCompileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
