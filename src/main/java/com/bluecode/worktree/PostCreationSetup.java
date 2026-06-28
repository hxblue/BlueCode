package com.bluecode.worktree;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class PostCreationSetup {
    private PostCreationSetup() {
    }

    static void run(Path repoRoot, Path wtPath, List<String> symlinkDirs) {
        step("copy config", () -> copyLocalConfigs(repoRoot, wtPath));
        step("git hooks", () -> setupGitHooks(repoRoot, wtPath));
        step("symlink dirs", () -> symlinkLargeDirs(repoRoot, wtPath, symlinkDirs));
        step("worktree include", () -> copyIncludedIgnored(repoRoot, wtPath));
    }

    private static void copyLocalConfigs(Path repoRoot, Path wtPath) throws IOException {
        for (String name : List.of("config.yaml", "settings.local.yaml")) {
            Path source = repoRoot.resolve(".bluecode").resolve(name);
            Path target = wtPath.resolve(".bluecode").resolve(name);
            if (!Files.isRegularFile(source) || Files.exists(target)) {
                continue;
            }
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private static void setupGitHooks(Path repoRoot, Path wtPath) throws IOException {
        Path hooksPath = null;
        Path husky = repoRoot.resolve(".husky");
        if (Files.isDirectory(husky)) {
            hooksPath = husky;
        } else {
            String configured = gitConfig(repoRoot, "core.hooksPath");
            if (!configured.isBlank()) {
                Path raw = Path.of(configured);
                hooksPath = raw.isAbsolute() ? raw : repoRoot.resolve(raw);
            }
        }
        if (hooksPath != null) {
            GitHelper.runGit(wtPath, "config", "core.hooksPath", hooksPath.toAbsolutePath().normalize().toString());
        }
    }

    private static String gitConfig(Path repoRoot, String key) {
        try {
            return GitHelper.runGit(repoRoot, "config", "--get", key);
        } catch (IOException e) {
            return "";
        }
    }

    private static void symlinkLargeDirs(Path repoRoot, Path wtPath, List<String> symlinkDirs) throws IOException {
        for (String dir : symlinkDirs == null ? List.<String>of() : symlinkDirs) {
            Path source = repoRoot.resolve(dir);
            Path target = wtPath.resolve(dir);
            if (!Files.exists(source) || Files.exists(target)) {
                continue;
            }
            Files.createSymbolicLink(target, source.toAbsolutePath().normalize());
        }
    }

    private static void copyIncludedIgnored(Path repoRoot, Path wtPath) throws IOException {
        Path include = repoRoot.resolve(".worktreeinclude");
        if (!Files.isRegularFile(include)) {
            return;
        }
        List<PathMatcher> matchers = Files.readAllLines(include).stream()
                .map(String::strip)
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
        if (matchers.isEmpty()) {
            return;
        }
        String ignored = GitHelper.runGit(repoRoot, "ls-files", "--others", "--ignored", "--exclude-standard", "--directory");
        for (String raw : ignored.split("\\R")) {
            String relativeText = raw.strip();
            if (relativeText.isBlank()) {
                continue;
            }
            Path relative = Path.of(relativeText.replace('/', java.io.File.separatorChar));
            if (!matches(matchers, relative)) {
                continue;
            }
            Path source = repoRoot.resolve(relative).normalize();
            Path target = wtPath.resolve(relative).normalize();
            if (!source.startsWith(repoRoot) || !target.startsWith(wtPath) || !Files.exists(source)) {
                continue;
            }
            copyRecursively(source, target);
        }
    }

    private static boolean matches(List<PathMatcher> matchers, Path relative) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relative) || matcher.matches(relative.getFileName())) {
                return true;
            }
        }
        return false;
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            List<Path> paths = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(source)) {
                paths.addAll(stream.toList());
            }
            for (Path path : paths) {
                Path dest = target.resolve(source.relativize(path)).normalize();
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
            return;
        }
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void step(String name, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            System.err.printf("worktree: setup %s: %s%n", name, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
