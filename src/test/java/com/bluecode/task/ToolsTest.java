package com.bluecode.task;

import com.bluecode.agent.Agent;
import com.bluecode.conversation.ConversationManager;
import com.bluecode.llm.LlmClient;
import com.bluecode.llm.Request;
import com.bluecode.llm.StreamEvent;
import com.bluecode.permission.PermissionEngine;
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

class ToolsTest {
    @TempDir
    Path tempDir;

    @Test
    void taskToolsExposeListGetStopAndSendMessage() throws Exception {
        Manager manager = new Manager();
        Agent agent = Agent.builder()
                .client(new FakeClient())
                .registry(new Registry())
                .engine(PermissionEngine.create(tempDir))
                .build();

        String id = manager.launch(agent, new ConversationManager(), "worker", "task");
        manager.subscribeDone().poll(5, TimeUnit.SECONDS);

        Result list = new TaskListTool(manager).execute(Map.of());
        assertFalse(list.isError());
        assertTrue(list.content().contains(id));

        Result get = new TaskGetTool(manager).execute(Map.of("task_id", id));
        assertFalse(get.isError());
        assertTrue(get.content().contains("\"status\":\"completed\""));

        Result send = new SendMessageTool(manager).execute(Map.of("name", "worker", "message", "again"));
        assertFalse(send.isError());
        assertTrue(send.content().contains("\"status\":\"resumed\""));

        Result stop = new TaskStopTool(manager).execute(Map.of("task_id", id));
        assertFalse(stop.isError());
    }

    private static final class FakeClient implements LlmClient {
        @Override
        public BlockingQueue<StreamEvent> stream(Request request) {
            LinkedBlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
            queue.add(new StreamEvent.TextDelta("ok"));
            queue.add(new StreamEvent.StreamEnd("stop", 1, 1));
            return queue;
        }

        @Override
        public String model() {
            return "fake";
        }
    }
}
