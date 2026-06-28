package com.bluecode.worktree;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorktreeManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void createEnterProtectAndRemoveWorktree() throws Exception {
        Path repo = initRepo();
        WorktreeManager manager = new WorktreeManager(repo, java.util.List.of());

        Worktree wt = manager.create("team/alice", "HEAD", true);
        assertTrue(Files.isDirectory(repo.resolve(".bluecode/worktrees/team+alice")));
        assertEquals("worktree-team+alice", wt.branch());
        assertTrue(manager.get("team/alice").isPresent());

        WorktreeManager restored = new WorktreeManager(repo, java.util.List.of());
        assertTrue(restored.get("team/alice").isPresent());

        WorktreeSession session = manager.enter("team/alice");
        assertEquals("team/alice", session.worktreeName());
        assertTrue(Files.readString(repo.resolve(".bluecode/worktree_session.json")).contains("worktree_path"));

        Files.writeString(wt.path().resolve("probe.txt"), "changed");
        assertThrows(WorktreeHasChangesException.class,
                () -> manager.exit("team/alice", ExitAction.REMOVE, new ExitOptions(false)));

        ExitReport report = manager.exit("team/alice", ExitAction.REMOVE, new ExitOptions(true));
        assertTrue(report.removed());
        assertFalse(Files.exists(wt.path()));
        assertEquals("null", Files.readString(repo.resolve(".bluecode/worktree_session.json")).strip());
    }

    @Test
    void nonGitRootFails() {
        assertThrows(IOException.class, () -> new WorktreeManager(tempDir.resolve("plain")));
    }

    private Path initRepo() throws Exception {
        Assumptions.assumeTrue(gitAvailable());
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo);
        run(repo, "git", "init");
        run(repo, "git", "config", "user.email", "test@example.com");
        run(repo, "git", "config", "user.name", "Test User");
        Files.writeString(repo.resolve("README.md"), "hello\n", StandardCharsets.UTF_8);
        run(repo, "git", "add", "README.md");
        run(repo, "git", "commit", "-m", "init");
        return repo;
    }

    private boolean gitAvailable() {
        try {
            run(tempDir, "git", "--version");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("command timeout: " + String.join(" ", command));
        }
        if (process.exitValue() != 0) {
            throw new IOException("command failed: " + String.join(" ", command) + "\n" + output);
        }
    }
}
