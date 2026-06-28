package com.bluecode.memory;

import com.bluecode.conversation.Message;
import com.bluecode.llm.LlmClient;
import com.bluecode.llm.Request;
import com.bluecode.llm.StreamEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryTest {
    @TempDir
    Path tempDir;

    @Test
    void storeCreatesUpdatesDeletesNotesAndIndex() throws Exception {
        Store store = new Store(tempDir.resolve("memory"));

        store.apply(List.of(new UpdateAction(
                "create", "project", "project_knowledge", "中文注释", "java_comments", "项目要求中文注释。", null)));
        Path note = tempDir.resolve("memory/project_knowledge_java_comments.md");
        assertTrue(Files.exists(note));
        assertTrue(Files.readString(note).contains("type: project_knowledge"));
        assertTrue(store.loadIndex().contains("[project_knowledge] 中文注释"));

        store.apply(List.of(new UpdateAction(
                "update", "project", null, "中文注释更新", null, "注释仍需中文。", note.getFileName().toString())));
        assertTrue(Files.readString(note).contains("中文注释更新"));
        assertTrue(store.loadIndex().contains("注释仍需中文"));

        store.apply(List.of(new UpdateAction(
                "delete", "project", null, null, null, null, note.getFileName().toString())));
        assertFalse(Files.exists(note));
        assertFalse(store.loadIndex().contains("中文注释更新"));
    }

    @Test
    void managerMergesAndTruncatesIndex() throws Exception {
        Path project = tempDir.resolve("project");
        Path user = tempDir.resolve("user");
        Files.createDirectories(project);
        Files.createDirectories(user);
        Files.writeString(project.resolve("MEMORY.md"), "- [project_knowledge] A — " + "x".repeat(26000), StandardCharsets.UTF_8);
        Files.writeString(user.resolve("MEMORY.md"), "- [user_preference] B — y", StandardCharsets.UTF_8);

        String index = new Manager(project, user, null, "").loadIndex();

        assertTrue(index.contains("[project_knowledge] A"));
        assertTrue(index.contains("(index truncated)"));
        assertTrue(index.getBytes(StandardCharsets.UTF_8).length <= 25 * 1024);
    }

    @Test
    void managerUpdateAsyncParsesJsonAndDoesNotSendTools() throws Exception {
        String json = """
                [{"action":"create","level":"user","type":"user_preference","title":"简洁回复","slug":"terse_replies","content":"用户偏好简洁回复。"}]
                """;
        FakeClient client = new FakeClient(json);
        Manager manager = new Manager(tempDir.resolve("project"), tempDir.resolve("user"), client, "fake");

        manager.updateAsync(List.of(
                new Message(Message.Role.USER, "记住：回复简洁点"),
                new Message(Message.Role.ASSISTANT, "好的")));

        Path note = tempDir.resolve("user/user_preference_terse_replies.md");
        waitUntilExists(note);
        assertTrue(Files.readString(note).contains("用户偏好简洁回复"));
        assertTrue(Files.readString(tempDir.resolve("user/MEMORY.md")).contains("[user_preference] 简洁回复"));
        assertTrue(client.lastRequest.tools().isEmpty());
    }

    private void waitUntilExists(Path path) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待文件生成超时: " + path);
    }

    private static final class FakeClient implements LlmClient {
        private final String text;
        private volatile Request lastRequest;

        private FakeClient(String text) {
            this.text = text;
        }

        @Override
        public BlockingQueue<StreamEvent> stream(Request request) {
            lastRequest = request;
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
