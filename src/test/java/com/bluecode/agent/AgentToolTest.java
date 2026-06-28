package com.bluecode.agent;

import com.bluecode.llm.LlmClient;
import com.bluecode.llm.Request;
import com.bluecode.llm.StreamEvent;
import com.bluecode.permission.PermissionEngine;
import com.bluecode.subagent.Catalog;
import com.bluecode.task.Manager;
import com.bluecode.tool.Registry;
import com.bluecode.tool.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
