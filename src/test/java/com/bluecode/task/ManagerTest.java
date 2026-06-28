package com.bluecode.task;

import com.bluecode.agent.Agent;
import com.bluecode.conversation.ConversationManager;
import com.bluecode.llm.LlmClient;
import com.bluecode.llm.Request;
import com.bluecode.llm.StreamEvent;
import com.bluecode.permission.PermissionEngine;
import com.bluecode.tool.Registry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void launchCompletesAndPublishesDone() throws Exception {
        Manager manager = new Manager();
        Agent agent = agent("done");

        String id = manager.launch(agent, new ConversationManager(), "worker", "task");
        String done = manager.subscribeDone().poll(5, TimeUnit.SECONDS);

        assertEquals(id, done);
        BackgroundTask task = manager.get(id).orElseThrow();
        assertEquals(Status.COMPLETED, task.status());
        assertEquals("done", task.result());
        assertNotNull(task.endTime());
    }

    @Test
    void sendMessageRerunsCompletedTask() throws Exception {
        Manager manager = new Manager();
        Agent agent = agent("first", "second");

        String id = manager.launch(agent, new ConversationManager(), "worker", "first task");
        assertEquals(id, manager.subscribeDone().poll(5, TimeUnit.SECONDS));

        String resumed = manager.sendMessage("worker", "second task");
        assertEquals(id, resumed);
        assertEquals(id, manager.subscribeDone().poll(5, TimeUnit.SECONDS));
        assertEquals("second", manager.get(id).orElseThrow().result());
    }

    private Agent agent(String... texts) {
        return Agent.builder()
                .client(new FakeClient(texts))
                .registry(new Registry())
                .engine(PermissionEngine.create(tempDir))
                .build();
    }

    private static final class FakeClient implements LlmClient {
        private final List<String> texts;
        private int index;

        private FakeClient(String... texts) {
            this.texts = List.of(texts);
        }

        @Override
        public BlockingQueue<StreamEvent> stream(Request request) {
            LinkedBlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
            String text = texts.get(Math.min(index++, texts.size() - 1));
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
