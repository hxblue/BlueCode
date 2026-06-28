package com.bluecode.instructions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public final class Loader {
    private static final Logger LOGGER = Logger.getLogger(Loader.class.getName());
    private static final String FILE_NAME = "MEWCODE.md";
    private static final int DEFAULT_MAX_DEPTH = 5;

    private final Path projectRoot;
    private final Path userHome;
    private final int maxDepth;

    public Loader(Path projectRoot) {
        this(projectRoot, Path.of(System.getProperty("user.home")), DEFAULT_MAX_DEPTH);
    }

    Loader(Path projectRoot, Path userHome, int maxDepth) {
        this.projectRoot = normalize(projectRoot == null ? Path.of("") : projectRoot);
        this.userHome = normalize(userHome == null ? Path.of(System.getProperty("user.home")) : userHome);
        this.maxDepth = Math.max(1, maxDepth);
    }

    public String load() throws IOException {
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, projectRoot.resolve(FILE_NAME), projectRoot);
        addIfPresent(parts, projectRoot.resolve(".bluecode").resolve(FILE_NAME), projectRoot);
        Path userBluecode = userHome.resolve(".bluecode");
        addIfPresent(parts, userBluecode.resolve(FILE_NAME), userBluecode);
        return String.join("\n\n", parts);
    }

    String loadFile(Path file, Path boundary, int depth, Set<Path> visited) throws IOException {
        if (depth > maxDepth) {
            return warn("<!-- @include 超过最大嵌套深度，已跳过: " + file + " -->");
        }

        Path normalizedFile = normalize(file);
        Path normalizedBoundary = normalize(boundary);
        if (!normalizedFile.startsWith(normalizedBoundary)) {
            return warn("<!-- @include 路径超出允许范围，已跳过: " + file + " -->");
        }
        if (!Files.exists(normalizedFile)) {
            return "";
        }

        Path absoluteFile;
        Path absoluteBoundary;
        try {
            absoluteFile = normalizedFile.toRealPath();
            absoluteBoundary = Files.exists(normalizedBoundary) ? normalizedBoundary.toRealPath() : normalizedBoundary;
        } catch (NoSuchFileException e) {
            return "";
        }
        if (!absoluteFile.startsWith(absoluteBoundary)) {
            return warn("<!-- @include 路径超出允许范围，已跳过: " + file + " -->");
        }
        if (visited.contains(absoluteFile)) {
            return warn("<!-- @include 检测到环路，已跳过: " + file + " -->");
        }

        byte[] bytes = Files.readAllBytes(absoluteFile);
        if (isBinary(bytes)) {
            return warn("<!-- @include 文件不可读或疑似二进制，已跳过: " + file + " -->");
        }

        Set<Path> chain = new HashSet<>(visited);
        chain.add(absoluteFile);
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\\R", -1);
        List<String> out = new ArrayList<>(lines.length);
        for (String line : lines) {
            if (line.startsWith("@include ") && line.length() > "@include ".length()
                    && line.equals(line.strip())) {
                Path included = absoluteFile.getParent().resolve(line.substring("@include ".length()).strip());
                out.add(loadFile(included, absoluteBoundary, depth + 1, new HashSet<>(chain)));
            } else {
                out.add(line);
            }
        }
        return String.join("\n", out).stripTrailing();
    }

    private void addIfPresent(List<String> parts, Path file, Path boundary) throws IOException {
        String value = loadFile(file, boundary, 1, Set.of()).strip();
        if (!value.isBlank()) {
            parts.add(value);
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(512, bytes.length);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String warn(String message) {
        LOGGER.warning(message);
        return message;
    }
}
