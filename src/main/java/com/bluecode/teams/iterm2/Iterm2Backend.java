package com.bluecode.teams.iterm2;

import com.bluecode.team.BackendType;
import com.bluecode.teams.Backend;
import com.bluecode.teams.BackendFactory;
import com.bluecode.teams.SpawnRequest;
import com.bluecode.teams.SpawnResult;
import com.bluecode.teams.tmux.TmuxBackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class Iterm2Backend implements Backend {
    private final BackendFactory.Deps deps;

    public Iterm2Backend(BackendFactory.Deps deps) {
        this.deps = deps == null ? BackendFactory.Deps.empty() : deps;
    }

    @Override
    public BackendType type() {
        return BackendType.ITERM2;
    }

    @Override
    public SpawnResult spawn(SpawnRequest request) throws IOException {
        String command = new TmuxBackend(deps).buildMemberCommand(request);
        Process process = new ProcessBuilder("it2", "split", "--new-pane", "--command", command)
                .redirectErrorStream(true)
                .start();
        String output = waitAndRead(process, "it2 split");
        return new SpawnResult(output.strip(), request.agentId());
    }

    @Override
    public void wake(String paneId, String agentId) throws IOException {
        if (paneId == null || paneId.isBlank()) {
            return;
        }
        waitAndRead(new ProcessBuilder("it2", "send-text", "--pane", paneId, "").redirectErrorStream(true).start(),
                "it2 send-text");
    }

    @Override
    public void kill(String paneId, String agentId) throws IOException {
        if (paneId == null || paneId.isBlank()) {
            return;
        }
        try {
            waitAndRead(new ProcessBuilder("it2", "close-pane", "--pane", paneId).redirectErrorStream(true).start(),
                    "it2 close-pane");
        } catch (IOException ignored) {
            // pane 已不存在时视为已清理。
        }
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
