package com.bluecode.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriterTest {
    @TempDir
    Path tempDir;

    @Test
    void pathReturnsConversationJsonlFile() throws Exception {
        Path sessionDir = tempDir.resolve("session");

        try (Writer writer = Writer.create(sessionDir)) {
            assertEquals(sessionDir.resolve("conversation.jsonl").toAbsolutePath().normalize(), writer.path());
            assertTrue(Files.exists(writer.path()));
        }
    }
}
