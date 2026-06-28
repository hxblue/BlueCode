package com.bluecode.subagent;

import com.bluecode.permission.Mode;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class Parser {
    public static final Pattern AGENT_NAME_REGEX = Pattern.compile("^[A-Za-z][A-Za-z0-9-_]{0,31}$");
    private static final String UTF8_BOM = "\uFEFF";

    private Parser() {
    }

    public static Definition parseFile(Path path, Definition.Source source) throws IOException, ParserException {
        return parseDefinition(Files.readAllBytes(path), path.toAbsolutePath().normalize().toString(), source);
    }

    public static Definition parseDefinition(byte[] data, String filePath, Definition.Source source)
            throws ParserException {
        Parsed parsed = parseFrontmatterAndBody(new String(data == null ? new byte[0] : data, StandardCharsets.UTF_8));
        Map<?, ?> meta;
        try {
            Object loaded = new Yaml().load(parsed.frontmatter());
            if (!(loaded instanceof Map<?, ?> map)) {
                throw new ParserException("frontmatter 必须是对象: " + filePath);
            }
            meta = map;
        } catch (YAMLException e) {
            throw new ParserException("frontmatter YAML 解析失败: " + e.getMessage(), e);
        }

        String name = string(meta, "name");
        if (name.isBlank()) {
            throw new ParserException("Agent 定义缺少 name: " + filePath);
        }
        if (!AGENT_NAME_REGEX.matcher(name).matches()) {
            throw new ParserException("Agent name 非法: " + name);
        }

        String description = string(meta, "description");
        if (description.isBlank()) {
            throw new ParserException("Agent 定义缺少 description: " + filePath);
        }

        String model = string(meta, "model");
        if (model.isBlank()) {
            model = "inherit";
        }
        if (!List.of("inherit", "haiku", "sonnet", "opus").contains(model)) {
            System.err.printf("[subagent] warn: %s unknown model \"%s\", defaulting to inherit%n", filePath, model);
            model = "inherit";
        }

        String isolation = string(meta, "isolation");
        if (!isolation.isBlank() && !"worktree".equals(isolation)) {
            System.err.printf("[subagent] warn: %s unknown isolation \"%s\", defaulting to none%n", filePath, isolation);
            isolation = "";
        }

        String modeText = string(meta, "permissionMode");
        boolean dontAsk = "dontAsk".equalsIgnoreCase(modeText);
        Mode mode = Mode.DEFAULT;
        if (!modeText.isBlank() && !dontAsk) {
            mode = Mode.parse(modeText).orElseGet(() -> {
                System.err.printf("[subagent] warn: %s unknown permissionMode \"%s\", defaulting to default%n",
                        filePath, modeText);
                return Mode.DEFAULT;
            });
        }

        return new Definition(
                name,
                description,
                stringList(meta, "tools"),
                stringList(meta, "disallowedTools"),
                model,
                intValue(meta, "maxTurns"),
                mode,
                dontAsk,
                boolValue(meta, "background"),
                parsed.body(),
                filePath,
                source,
                isolation);
    }

    private static Parsed parseFrontmatterAndBody(String raw) throws ParserException {
        String text = raw == null ? "" : raw;
        if (text.startsWith(UTF8_BOM)) {
            text = text.substring(1);
        }
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!text.startsWith("---\n")) {
            throw new ParserException("Agent 定义必须以 YAML frontmatter 开头");
        }
        int end = text.indexOf("\n---", 4);
        if (end < 0) {
            throw new ParserException("Agent 定义 frontmatter 未关闭");
        }
        int bodyStart = end + "\n---".length();
        if (bodyStart < text.length() && text.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        return new Parsed(text.substring(4, end), text.substring(bodyStart).stripLeading());
    }

    private static String string(Map<?, ?> meta, String key) {
        Object value = meta.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static List<String> stringList(Map<?, ?> meta, String key) throws ParserException {
        Object value = meta.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new ParserException(key + " 必须是字符串数组");
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item).trim());
            }
        }
        return List.copyOf(result);
    }

    private static int intValue(Map<?, ?> meta, String key) throws ParserException {
        Object value = meta.get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new ParserException(key + " 必须是整数", e);
        }
    }

    private static boolean boolValue(Map<?, ?> meta, String key) {
        Object value = meta.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private record Parsed(String frontmatter, String body) {
    }

    public static class ParserException extends Exception {
        public ParserException(String message) {
            super(message);
        }

        public ParserException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
