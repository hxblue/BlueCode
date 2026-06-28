package com.bluecode.compact.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionContextTest {
    @TempDir
    Path tempDir;

    @Test
    void createsNewReadableSessionIdAndDirs() throws Exception {
        SessionContext context = SessionContext.create(tempDir);

        assertTrue(context.sessionId().matches("\\d{8}-\\d{6}-[0-9a-f]{4}"));
        assertDoesNotThrow(() -> SessionContext.parseSessionTime(context.sessionId()));
        assertTrue(Files.isDirectory(context.sessionDir()));
        assertTrue(Files.isDirectory(context.spillDir()));
        assertTrue(context.spillDir().startsWith(context.sessionDir()));
    }
}
