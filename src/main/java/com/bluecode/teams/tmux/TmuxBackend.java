package com.bluecode.teams.tmux;

import com.bluecode.team.BackendType;
import com.bluecode.teams.Backend;
import com.bluecode.teams.BackendFactory;
import com.bluecode.teams.SpawnRequest;
import com.bluecode.teams.SpawnResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class TmuxBackend implements Backend {
    private final BackendFactory.Deps deps;

    public TmuxBackend(BackendFactory.Deps deps) {
        this.deps = deps == null ? BackendFactory.Deps.empty() : deps;
    }

    @Override
    public BackendType type() {
        return BackendType.TMUX;
    }

    @Override
    public SpawnResult spawn(SpawnRequest request) throws IOException {
        String command = buildMemberCommand(request);
        Process process = new ProcessBuilder("tmux", "split-window", "-h", "-P", "-F", "#{pane_id}", "--", command)
                .redirectErrorStream(true)
                .start();
        String output = waitAndRead(process, "tmux split-window");
        return new SpawnResult(output.strip(), request.agentId());
    }

    @Override
    public void wake(String paneId, String agentId) throws IOException {
        if (paneId == null || paneId.isBlank()) {
            return;
        }
        run("tmux", "send-keys", "-t", paneId, "", "Enter");
    }

    @Override
    public void kill(String paneId, String agentId) throws IOException {
        if (paneId == null || paneId.isBlank()) {
            return;
        }
        try {
            run("tmux", "kill-pane", "-t", paneId);
        } catch (IOException ignored) {
            // pane 已不存在时视为已清理。
        }
    }

    public String buildMemberCommand(SpawnRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add("java");
        parts.add("-jar");
        parts.add(jarPath());
        parts.add("--team-member");
        add(parts, "--team", request.teamName());
        add(parts, "--member", request.memberName());
        add(parts, "--agent-id", request.agentId());
        add(parts, "--session-dir", request.sessionDir());
        add(parts, "--worktree", request.worktreePath());
        if (!request.agentType().isBlank()) {
            add(parts, "--agent-type", request.agentType());
        }
        if (!request.model().isBlank()) {
            add(parts, "--model", request.model());
        }
        if (request.planModeRequired()) {
            parts.add("--plan-mode");
        }
        return quote(parts);
    }

    private String jarPath() {
        Path jar = deps.jarPath();
        if (jar != null) {
            return jar.toAbsolutePath().normalize().toString();
        }
        return deps.projectRoot().resolve("build").resolve("libs").resolve("bluecode.jar").toString();
    }

    private static void add(List<String> parts, String flag, String value) {
        parts.add(flag);
        parts.add(value == null ? "" : value);
    }

    private static String quote(List<String> parts) {
        return parts.stream()
                .map(TmuxBackend::shellQuote)
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private static String shellQuote(String value) {
        if (value == null || value.isEmpty()) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void run(String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        waitAndRead(process, String.join(" ", command));
    }

    private static String waitAndRead(Process process, String label) throws IOException {
        try {
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException(label + " timeout");
            }
            if (process.exitValue() != 0) {
                throw new IOException(label + " failed: " + output.strip());
            }
            return output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(label + " interrupted", e);
        }
    }
}
