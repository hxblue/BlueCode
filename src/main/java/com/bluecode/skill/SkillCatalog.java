package com.bluecode.skill;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class SkillCatalog {
    private final LinkedHashMap<String, Skill> skills = new LinkedHashMap<>();
    private final LinkedHashMap<String, Path> sources = new LinkedHashMap<>();
    private Path lastWorkDir = Path.of("").toAbsolutePath().normalize();

    public synchronized void register(Skill skill) {
        if (skill == null || skill.meta() == null || skill.name().isBlank()) {
            throw new IllegalArgumentException("skill 不能为空");
        }
        String name = normalizeName(skill.name());
        skills.put(name, skill.withName(name));
        if (skill.sourceDir() != null) {
            sources.put(name, skill.sourceDir().toAbsolutePath().normalize());
        } else {
            sources.remove(name);
        }
    }

    public synchronized Optional<Skill> get(String name) {
        return Optional.ofNullable(skills.get(normalizeName(name)));
    }

    public Optional<Skill> getFull(String name) {
        String normalized = normalizeName(name);
        Skill cached;
        synchronized (this) {
            cached = skills.get(normalized);
        }
        if (cached == null || cached.sourceDir() == null) {
            return Optional.ofNullable(cached);
        }
        try {
            Skill fresh = loadSkill(cached.sourceDir());
            if (fresh == null) {
                return Optional.of(cached);
            }
            Skill full = fresh.withName(normalized).withBody(fresh.promptBody());
            synchronized (this) {
                skills.put(normalized, full);
                sources.put(normalized, cached.sourceDir());
            }
            return Optional.of(full);
        } catch (IOException | RuntimeException ignored) {
            return Optional.of(cached);
        }
    }

    public synchronized List<Skill> list() {
        return List.copyOf(skills.values());
    }

    public synchronized Optional<Path> source(String name) {
        return Optional.ofNullable(sources.get(normalizeName(name)));
    }

    public synchronized SkillCatalog loadCatalog(Path workDir) {
        Path resolvedWorkDir = workDir == null ? Path.of("").toAbsolutePath().normalize() : workDir.toAbsolutePath().normalize();
        lastWorkDir = resolvedWorkDir;
        skills.clear();
        sources.clear();
        loadTier(userSkillsDir());
        loadTier(resolvedWorkDir.resolve(".bluecode").resolve("skills"));
        return this;
    }

    public synchronized void reload(Path workDir) {
        loadCatalog(workDir == null ? lastWorkDir : workDir);
    }

    public synchronized SkillCatalog loadFromDirectory(Path directory) {
        Path resolved = directory == null ? null : directory.toAbsolutePath().normalize();
        skills.clear();
        sources.clear();
        loadTier(resolved);
        return this;
    }

    public synchronized String buildActiveContext(Set<String> activeSkillNames) {
        if (activeSkillNames == null || activeSkillNames.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("## Active Skills");
        boolean any = false;
        for (String rawName : activeSkillNames) {
            Optional<Skill> skill = getFull(rawName);
            if (skill.isEmpty()) {
                continue;
            }
            any = true;
            builder.append("\n\n### ")
                    .append(skill.get().name())
                    .append("\n\n")
                    .append(skill.get().promptBody().strip());
        }
        return any ? builder.toString() : "";
    }

    public synchronized String buildCatalogContext() {
        if (skills.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("## Skills Catalog\n\n");
        builder.append("可用 skill 可通过 LoadSkill 工具或对应 slash 命令加载：");
        for (Skill skill : skills.values()) {
            builder.append("\n- ")
                    .append(skill.name());
            if (!skill.meta().description().isBlank()) {
                builder.append(": ").append(skill.meta().description());
            }
        }
        return builder.toString();
    }

    private void loadTier(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> dirs = stream
                    .filter(Files::isDirectory)
                    .sorted((left, right) -> left.getFileName().toString().compareTo(right.getFileName().toString()))
                    .toList();
            for (Path dir : dirs) {
                try {
                    Skill skill = loadSkill(dir);
                    if (skill != null) {
                        register(skill);
                    }
                } catch (IOException | RuntimeException ignored) {
                    // 单个 skill 解析失败不影响其余 skill 加载。
                }
            }
        } catch (IOException ignored) {
            // catalog 加载必须容错，目录不可读时静默跳过。
        }
    }

    static Skill loadSkill(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return null;
        }
        Path yaml = dir.resolve("skill.yaml");
        Path prompt = dir.resolve("prompt.md");
        if (Files.isRegularFile(yaml) && Files.isRegularFile(prompt)) {
            return loadFromYamlAndPrompt(dir, yaml, prompt);
        }
        Path skillMd = dir.resolve("SKILL.md");
        if (Files.isRegularFile(skillMd)) {
            return parseSkillMD(dir, skillMd);
        }
        return null;
    }

    private static Skill loadFromYamlAndPrompt(Path dir, Path yamlPath, Path promptPath) throws IOException {
        Object loaded = new Yaml().load(Files.readString(yamlPath, StandardCharsets.UTF_8));
        Map<String, Object> map = asMap(loaded);
        String body = Files.readString(promptPath, StandardCharsets.UTF_8);
        SkillMeta meta = metaFromMap(map, dir.getFileName().toString(), firstBodyLine(body));
        return new Skill(meta, body, dir.toAbsolutePath().normalize(), true);
    }

    private static Skill parseSkillMD(Path dir, Path skillMd) throws IOException {
        String text = Files.readString(skillMd, StandardCharsets.UTF_8);
        String body = text;
        Map<String, Object> map = Map.of();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("---\n")) {
            int end = findFrontmatterEnd(normalized);
            if (end > 0) {
                String frontmatter = normalized.substring(4, end);
                String parsedBody = normalized.substring(endDelimiterEnd(normalized, end));
                try {
                    map = asMap(new Yaml().load(frontmatter));
                    body = parsedBody;
                } catch (YAMLException | ClassCastException ignored) {
                    map = Map.of();
                    body = text;
                }
            }
        }
        SkillMeta meta = metaFromMap(map, dir.getFileName().toString(), firstBodyLine(body));
        return new Skill(meta, body, dir.toAbsolutePath().normalize(), true);
    }

    private static int findFrontmatterEnd(String text) {
        int newlineEnd = text.indexOf("\n---\n", 4);
        if (newlineEnd >= 0) {
            return newlineEnd + 1;
        }
        if (text.endsWith("\n---")) {
            return text.length() - 3;
        }
        return -1;
    }

    private static int endDelimiterEnd(String text, int delimiterStart) {
        int after = delimiterStart + 3;
        if (after < text.length() && text.charAt(after) == '\n') {
            after++;
        }
        return Math.min(after, text.length());
    }

    private static SkillMeta metaFromMap(Map<String, Object> map, String dirName, String fallbackDescription) {
        String name = normalizeName(firstString(map, "name"));
        if (name.isBlank()) {
            name = normalizeName(dirName);
        }
        String description = firstString(map, "description");
        if (description.isBlank()) {
            description = fallbackDescription == null ? "" : fallbackDescription.strip();
        }
        String whenToUse = firstString(map, "when_to_use", "whenToUse", "when");
        List<String> tags = stringList(firstObject(map, "tags"));
        List<String> allowedTools = stringList(firstObject(map, "allowed_tools", "allowedTools"));
        String mode = firstString(map, "mode");
        String context = firstString(map, "context");
        if (mode.isBlank() && "fork".equalsIgnoreCase(context)) {
            mode = "fork";
        }
        mode = normalizeMode(mode);
        String model = firstString(map, "model");
        String forkContext = firstString(map, "fork_context", "forkContext");
        if (forkContext.isBlank()) {
            forkContext = "none";
        }
        forkContext = normalizeForkContext(forkContext);
        return new SkillMeta(name, description, whenToUse, tags, allowedTools, mode, model, forkContext);
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "inline";
        }
        String normalized = mode.strip().toLowerCase(Locale.ROOT);
        return "fork".equals(normalized) ? "fork" : "inline";
    }

    private static String normalizeForkContext(String forkContext) {
        String normalized = forkContext == null ? "" : forkContext.strip().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "recent", "full" -> normalized;
            default -> "none";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object loaded) {
        if (loaded instanceof Map<?, ?> raw) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    map.put(entry.getKey().toString(), entry.getValue());
                }
            }
            return map;
        }
        return Map.of();
    }

    private static Object firstObject(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        Object value = firstObject(map, keys);
        return value == null ? "" : value.toString().strip();
    }

    private static List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                addString(result, item);
            }
        } else if (value.getClass().isArray()) {
            Object[] array = (Object[]) value;
            for (Object item : array) {
                addString(result, item);
            }
        } else {
            String text = value.toString();
            for (String item : text.split(",")) {
                addString(result, item);
            }
        }
        return List.copyOf(result);
    }

    private static void addString(List<String> result, Object value) {
        if (value == null) {
            return;
        }
        String text = value.toString().strip();
        if (!text.isBlank()) {
            result.add(text);
        }
    }

    private static String firstBodyLine(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        for (String line : body.split("\\R")) {
            String stripped = line.strip();
            if (!stripped.isBlank() && !stripped.startsWith("#")) {
                return stripped;
            }
        }
        return "";
    }

    private static Path userSkillsDir() {
        return Path.of(System.getProperty("user.home", ".")).resolve(".bluecode").resolve("skills");
    }

    public static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        String normalized = name.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
        normalized = normalized.replaceAll("[^a-z0-9._-]+", "-");
        normalized = normalized.replaceAll("-+", "-");
        while (normalized.startsWith("-")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("-")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    public record SkillMeta(
            String name,
            String description,
            String whenToUse,
            List<String> tags,
            List<String> allowedTools,
            String mode,
            String model,
            String forkContext) {
        public SkillMeta {
            name = normalizeName(name);
            description = description == null ? "" : description.strip();
            whenToUse = whenToUse == null ? "" : whenToUse.strip();
            tags = tags == null ? List.of() : List.copyOf(tags);
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
            mode = normalizeMode(mode);
            model = model == null ? "" : model.strip();
            forkContext = normalizeForkContext(forkContext);
        }
    }

    public record Skill(SkillMeta meta, String promptBody, Path sourceDir, boolean bodyLoaded) {
        public Skill {
            if (meta == null) {
                throw new IllegalArgumentException("skill meta 不能为空");
            }
            promptBody = promptBody == null ? "" : promptBody;
            sourceDir = sourceDir == null ? null : sourceDir.toAbsolutePath().normalize();
        }

        public String name() {
            return meta.name();
        }

        public Skill withBody(String body) {
            return new Skill(meta, body, sourceDir, true);
        }

        public Skill withName(String name) {
            return new Skill(new SkillMeta(
                    name,
                    meta.description(),
                    meta.whenToUse(),
                    meta.tags(),
                    meta.allowedTools(),
                    meta.mode(),
                    meta.model(),
                    meta.forkContext()), promptBody, sourceDir, bodyLoaded);
        }
    }
}
