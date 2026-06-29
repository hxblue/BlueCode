package com.bluecode.teams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MailboxTest {
    @TempDir
    Path tempDir;

    @Test
    void writeReadAndMarkRead() throws Exception {
        Mailbox mailbox = new Mailbox(tempDir);
        mailbox.write("agent-1", new Message("lead", "agent-1", MessageType.TEXT, "hi", "hello", Map.of(), 0, false));

        List<Message> messages = mailbox.read("agent-1");
        assertEquals(1, messages.size());
        assertEquals("hello", messages.getFirst().content());

        ReadUnreadResult unread = mailbox.readUnread("agent-1");
        assertEquals(List.of(0), unread.indices());
        mailbox.markRead("agent-1", unread.indices());
        assertTrue(mailbox.readUnread("agent-1").messages().isEmpty());
    }

    @Test
    void concurrentWritesDoNotDropMessages() throws Exception {
        Mailbox mailbox = new Mailbox(tempDir);
        CountDownLatch done = new CountDownLatch(10);
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int index = i;
            Thread.startVirtualThread(() -> {
                try {
                    mailbox.write("agent-1", new Message("lead", "agent-1", MessageType.TEXT,
                            "m" + index, "content" + index, Map.of(), 0, false));
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(10, mailbox.read("agent-1").size());
    }

    @Test
    void staleLockCanBeReclaimed() throws Exception {
        Path lock = tempDir.resolve("stale.lock");
        Files.writeString(lock, "");
        Files.setLastModifiedTime(lock, FileTime.from(Instant.now().minusSeconds(11)));

        try (AutoCloseable ignored = FileLock.acquire(lock)) {
            assertTrue(Files.exists(lock));
        }
        assertFalse(Files.exists(lock));
    }
}
