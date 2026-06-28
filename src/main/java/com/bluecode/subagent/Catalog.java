package com.bluecode.subagent;

import com.bluecode.permission.Mode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class Catalog {
    private final Object lock = new Object();
    private final Map<String, Definition> defs = new HashMap<>();
    private final EnumMap<Definition.Source, List<Definition>> bySource =
            new EnumMap<>(Definition.Source.class);

    public static Catalog load(Path root) {
        Path projectRoot = root == null ? Path.of("").toAbsolutePath().normalize() : root.toAbsolutePath().normalize();
        Catalog catalog = new Catalog();
        catalog.addAll(BuiltinLoader.builtinDefinitions(), Definition.Source.BUILTIN);
        catalog.addAll(loadFromDir(Path.of(System.getProperty("user.home", ".")).resolve(".bluecode").resolve("agents"),
                Definition.Source.USER), Definition.Source.USER);
        catalog.addAll(loadFromDir(projectRoot.resolve(".bluecode").resolve("agents"),
                Definition.Source.PROJECT), Definition.Source.PROJECT);
        return catalog;
    }

    public Optional<Definition> resolve(String name) {
        synchronized (lock) {
            return Optional.ofNullable(defs.get(name));
        }
    }

    public List<Definition> list() {
        synchronized (lock) {
            return defs.values().stream()
                    .sorted(Comparator.comparing(Definition::name))
                    .toList();
        }
    }

    public List<Definition> listBySource(Definition.Source source) {
        synchronized (lock) {
            return List.copyOf(bySource.getOrDefault(source, List.of()));
        }
    }

    public Definition forkDefinition() {
        return new Definition(
                "__fork__",
                "Fork-based subagent",
                List.of(),
                List.of(),
                "inherit",
                25,
                Mode.DEFAULT,
                false,
                true,
                "",
                "",
                Definition.Source.BUILTIN,
                "");
    }

    private static List<Definition> loadFromDir(Path dir, Definition.Source source) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        List<Definition> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList()) {
                try {
                    result.add(Parser.parseFile(path, source));
                } catch (Exception e) {
                    System.err.printf("[subagent] warn: skip %s: %s%n", path, e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.printf("[subagent] warn: load dir %s failed: %s%n", dir, e.getMessage());
        }
        return List.copyOf(result);
    }

    private void addAll(List<Definition> items, Definition.Source source) {
        synchronized (lock) {
            List<Definition> sourceItems = bySource.computeIfAbsent(source, ignored -> new ArrayList<>());
            for (Definition definition : items == null ? List.<Definition>of() : items) {
                defs.put(definition.name(), definition);
                sourceItems.add(definition);
            }
        }
    }
}
