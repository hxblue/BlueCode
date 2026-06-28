package com.bluecode.worktree;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class GitHelper {
    private GitHelper() {
    }

    public static ProcessBuilder gitProcess(Path workDir, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        if (args != null) {
            command.addAll(List.of(args));
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory((workDir == null ? Path.of("").toAbsolutePath() : workDir).toFile());
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_ASKPASS", "");
        builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        return builder;
    }

    public static String runGit(Path workDir, String... args) throws IOException {
        Process process = gitProcess(workDir, args).start();
        byte[] stdout;
        byte[] stderr;
        try {
            stdout = process.getInputStream().readAllBytes();
            stderr = process.getErrorStream().readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) {
                String message = new String(stderr, StandardCharsets.UTF_8).strip();
                throw new IOException(message.isBlank() ? "git exited with " + exit : message);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("git interrupted", e);
        }
        return new String(stdout, StandardCharsets.UTF_8).stripTrailing();
    }

    public static boolean hasWorktreeChanges(Path wtPath, String baseCommit) {
        try {
            String status = runGit(wtPath, "status", "--porcelain");
            if (!status.isBlank()) {
                return true;
            }
            String base = baseCommit == null || baseCommit.isBlank() ? "HEAD" : baseCommit;
            String countText = runGit(wtPath, "rev-list", "--count", base + "..HEAD").strip();
            return Integer.parseInt(countText.isBlank() ? "0" : countText) > 0;
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean hasUnpushedCommits(Path wtPath) {
        try {
            String output = runGit(wtPath, "rev-list", "--max-count=1", "HEAD", "--not", "--remotes");
            return !output.isBlank();
        } catch (IOException e) {
            return true;
        }
    }

    public static Optional<String> resolveHeadShaFromFS(Path wtPath) {
        try {
            Path gitPointer = wtPath.resolve(".git");
            if (!Files.exists(gitPointer)) {
                return Optional.empty();
            }
            Path gitDir = resolveGitDir(wtPath, gitPointer);
            Path headFile = gitDir.resolve("HEAD");
            if (!Files.exists(headFile)) {
                return Optional.empty();
            }
            String head = Files.readString(headFile, StandardCharsets.UTF_8).strip();
            if (isSha(head)) {
                return Optional.of(head);
            }
            if (!head.startsWith("ref:")) {
                return Optional.empty();
            }
            String ref = head.substring("ref:".length()).strip();
            Optional<String> direct = readSha(gitDir.resolve(ref));
            if (direct.isPresent()) {
                return direct;
            }
            Path commonDir = resolveCommonDir(gitDir);
            direct = readSha(commonDir.resolve(ref));
            if (direct.isPresent()) {
                return direct;
            }
            return readPackedRef(commonDir.resolve("packed-refs"), ref);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Path resolveGitDir(Path wtPath, Path gitPointer) throws IOException {
        if (Files.isDirectory(gitPointer)) {
            return gitPointer.toAbsolutePath().normalize();
        }
        String text = Files.readString(gitPointer, StandardCharsets.UTF_8).strip();
        if (!text.startsWith("gitdir:")) {
            throw new IOException(".git 指针无效");
        }
        String raw = text.substring("gitdir:".length()).strip();
        Path path = Path.of(raw);
        if (!path.isAbsolute()) {
            path = wtPath.resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static Path resolveCommonDir(Path gitDir) {
        Path commonFile = gitDir.resolve("commondir");
        try {
            if (Files.exists(commonFile)) {
                String text = Files.readString(commonFile, StandardCharsets.UTF_8).strip();
                Path common = Path.of(text);
                if (!common.isAbsolute()) {
                    common = gitDir.resolve(common);
                }
                return common.toAbsolutePath().normalize();
            }
        } catch (IOException ignored) {
            // 读不到 commondir 时回退到 gitDir。
        }
        return gitDir;
    }

    private static Optional<String> readSha(Path file) throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String sha = Files.readString(file, StandardCharsets.UTF_8).strip();
        return isSha(sha) ? Optional.of(sha) : Optional.empty();
    }

    private static Optional<String> readPackedRef(Path file, String ref) throws IOException {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isBlank() || line.startsWith("^")) {
                continue;
            }
            String[] parts = line.split("\\s+", 2);
            if (parts.length == 2 && ref.equals(parts[1]) && isSha(parts[0])) {
                return Optional.of(parts[0]);
            }
        }
        return Optional.empty();
    }

    private static boolean isSha(String value) {
        return value != null && value.matches("[0-9a-fA-F]{40}");
    }

    private static File nullDevice() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return new File(windows ? "NUL" : "/dev/null");
    }
}
