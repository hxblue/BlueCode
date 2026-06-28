package com.bluecode.session;

import com.bluecode.conversation.Message;
import com.bluecode.llm.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTest {
    @TempDir
    Path tempDir;

    @Test
    void writerAppendsJsonlWithModelOnFirstLine() throws Exception {
        Path dir = tempDir.resolve("20260601-120000-a1b2");

        try (Writer writer = Writer.create(dir)) {
            writer.append(new Message(Message.Role.USER, "hello"), "fake-model", true);
            writer.append(new Message(Message.Role.ASSISTANT, "hi"), "fake-model", false);
        }

        List<String> lines = Files.readAllLines(dir.resolve("conversation.jsonl"), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.getFirst().contains("\"role\":\"user\""));
        assertTrue(lines.getFirst().contains("\"model\":\"fake-model\""));
        assertTrue(lines.getLast().contains("\"role\":\"assistant\""));
    }

    @Test
    void loaderUsesMessagesAfterLastCompactAndSkipsBadLines() throws Exception {
        Path dir = tempDir.resolve("20260601-120000-a1b2");
        try (Writer writer = Writer.create(dir)) {
            writer.append(new Message(Message.Role.USER, "old"), "fake", true);
            writer.writeCompactMarker();
            writer.append(new Message(Message.Role.USER, "new"), null, false);
        }
        Files.writeString(dir.resolve("conversation.jsonl"), "{bad json\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        List<Message> loaded = SessionLoader.load(dir);

        assertEquals(1, loaded.size());
        assertEquals("new", loaded.getFirst().content());
    }

    @Test
    void loaderTruncatesTailAssistantToolCallWithoutToolResult() throws Exception {
        Path dir = tempDir.resolve("20260601-120000-a1b2");
        try (Writer writer = Writer.create(dir)) {
            writer.append(new Message(Message.Role.USER, "read"), "fake", true);
            writer.append(new Message(
                    Message.Role.ASSISTANT,
                    "tool",
                    List.of(new ToolCall("call-1", "ReadFile", "{}")),
                    List.of()), null, false);
        }

        List<Message> loaded = SessionLoader.load(dir);

        assertEquals(1, loaded.size());
        assertEquals(Message.Role.USER, loaded.getFirst().role());
    }

    @Test
    void listSessionsSortsNewFormatAndSkipsOldFormat() throws Exception {
        Path sessions = tempDir.resolve("sessions");
        Path old = sessions.resolve("1717000000-abc12345");
        Path first = sessions.resolve("20260601-120000-a1b2");
        Path second = sessions.resolve("20260602-120000-a1b2");
        Files.createDirectories(old);
        Files.writeString(old.resolve("conversation.jsonl"), "{}");
        writeOne(second, "第二个会话", "m2");
        writeOne(first, "第一个会话", "m1");
        Files.setLastModifiedTime(first.resolve("conversation.jsonl"), FileTime.from(Instant.parse("2026-06-01T00:00:00Z")));
        Files.setLastModifiedTime(second.resolve("conversation.jsonl"), FileTime.from(Instant.parse("2026-06-02T00:00:00Z")));

        List<SessionInfo> listed = SessionList.list(sessions);

        assertEquals(2, listed.size());
        assertEquals("20260602-120000-a1b2", listed.getFirst().id());
        assertEquals("第二个会话", listed.getFirst().title());
        assertEquals("m2", listed.getFirst().model());
    }

    @Test
    void cleanerDeletesOnlyExpiredNewFormatSessions() throws Exception {
        Path sessions = tempDir.resolve("sessions");
        Path expired = sessions.resolve("20000101-120000-dead");
        Path fresh = sessions.resolve("29990101-120000-beef");
        Path oldFormat = sessions.resolve("1717000000-abc12345");
        Files.createDirectories(expired);
        Files.createDirectories(fresh);
        Files.createDirectories(oldFormat);

        SessionCleaner.cleanExpired(sessions, Duration.ofDays(30));

        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(fresh));
        assertTrue(Files.exists(oldFormat));
    }

    private void writeOne(Path dir, String user, String model) throws Exception {
        try (Writer writer = Writer.create(dir)) {
            writer.append(new Message(Message.Role.USER, user), model, true);
        }
    }
}
