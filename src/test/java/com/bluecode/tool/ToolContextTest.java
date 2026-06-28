package com.bluecode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolContextTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void resolvePathUsesExplicitCwdForRelativePaths() {
        ToolContext ctx = ToolContext.root().withCwd(tempDir);

        assertEquals(tempDir.resolve("a.txt").normalize(), ctx.resolvePath("a.txt"));
        assertEquals(tempDir.toAbsolutePath().normalize(), ctx.resolvePath(""));
    }

    @Test
    void readAndWriteUseExplicitCwdWithoutChangingSchema() throws Exception {
        Registry registry = Registry.createDefault();
        ToolContext ctx = ToolContext.root().withCwd(tempDir);

        Result write = registry.execute(ctx, "WriteFile", json(Map.of("path", "probe.txt", "content", "hello")));
        Result read = registry.execute(ctx, "ReadFile", json(Map.of("path", "probe.txt")));

        assertFalse(write.isError());
        assertFalse(read.isError());
        assertEquals("hello", Files.readString(tempDir.resolve("probe.txt")));
        assertTrue(read.content().contains("hello"));
        assertTrue(registry.get("ReadFile").orElseThrow().schema().toString().contains("path"));
        assertFalse(registry.get("ReadFile").orElseThrow().schema().toString().contains("cwd"));
    }

    private String json(Map<String, String> value) throws Exception {
        return MAPPER.writeValueAsString(value);
    }
}
