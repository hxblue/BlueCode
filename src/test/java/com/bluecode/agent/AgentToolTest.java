package com.bluecode.agent;

import com.bluecode.llm.LlmClient;
import com.bluecode.llm.Request;
import com.bluecode.llm.StreamEvent;
import com.bluecode.permission.PermissionEngine;
import com.bluecode.subagent.Catalog;
import com.bluecode.task.Manager;
import com.bluecode.tool.Registry;
import com.bluecode.tool.Result;
import com.bluecode.worktree.WorktreeManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolTest {
    @TempDir
    Path tempDir;

    @Test
    void runsDefinedSubAgentInline() {
        Registry registry = Registry.createDefault();
        Agent parent = Agent.builder()
                .client(new FakeClient("child done"))
                .registry(registry)
                .engine(PermissionEngine.create(tempDir))
                .build();
        AgentTool tool = new AgentTool(Catalog.load(tempDir), new Manager(), parent, true);

        Result result = tool.execute(Map.of(
                "prompt", "say done",
                "description", "test",
                "subagent_type", "Explore"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("child done"));
    }

    @Test
    void launchesBackgroundSubAgent() throws Exception {
        Registry registry = Registry.createDefault();
        Agent parent = Agent.builder()
                .client(new FakeClient("background done"))
                .registry(registry)
                .engine(PermissionEngine.create(tempDir))
                .build();
        Manager manager = new Manager();
        AgentTool tool = new AgentTool(Catalog.load(tempDir), manager, parent, true);

        Result result = tool.execute(Map.of(
                "prompt", "work",
                "description", "test",
                "subagent_type", "general-purpose",
                "run_in_background", true,
                "name", "worker"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("async_launched"));
        assertTrue(manager.subscribeDone().poll(5, TimeUnit.SECONDS).startsWith("task_"));
    }

    @Test
    void unknownSubAgentTypeReturnsError() {
        Agent parent = Agent.builder()
                .client(new FakeClient("unused"))
                .registry(Registry.createDefault())
                .engine(PermissionEngine.create(tempDir))
                .build();
        AgentTool tool = new AgentTool(Catalog.load(tempDir), new Manager(), parent, true);

        Result result = tool.execute(Map.of(
                "prompt", "work",
                "description", "test",
                "subagent_type", "missing"));

        assertTrue(result.isError());
        assertTrue(result.content().contains("未知 subagent_type"));
    }

    @Test
    void isolationWorktreeRunsInlineAndCleansUnchangedWorktree() throws Exception {
        Path repo = initRepo();
        Path agents = repo.resolve(".bluecode").resolve("agents");
        Files.createDirectories(agents);
        Files.writeString(agents.resolve("isolated.md"), """
                ---
                name: isolated
                description: isolated worker
                isolation: worktree
                maxTurns: 2
                ---
                只完成任务并返回结果。
                """, StandardCharsets.UTF_8);
        WorktreeManager worktreeManager = new WorktreeManager(repo, java.util.List.of());
        Registry registry = Registry.createDefault();
        Agent parent = Agent.builder()
                .client(new FakeClient("isolated done"))
                .registry(registry)
                .engine(PermissionEngine.create(repo))
                .build();
        AgentTool tool = new AgentTool(Catalog.load(repo), new Manager(), parent, true, worktreeManager);

        Result result = tool.execute(Map.of(
                "prompt", "say done",
                "description", "test",
                "subagent_type", "isolated"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("isolated done"));
        Path worktrees = repo.resolve(".bluecode").resolve("worktrees");
        if (Files.exists(worktrees)) {
            try (var stream = Files.list(worktrees)) {
                assertTrue(stream.findAny().isEmpty());
            }
        }
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

    private static final class FakeClient implements LlmClient {
        private final String text;

        private FakeClient(String text) {
            this.text = text;
        }

        @Override
        public BlockingQueue<StreamEvent> stream(Request request) {
            LinkedBlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
            queue.add(new StreamEvent.TextDelta(text));
            queue.add(new StreamEvent.StreamEnd("stop", 1, 1));
            return queue;
        }

        @Override
        public String model() {
            return "fake";
        }
    }
}
