package com.bluecode.mcp;

import com.bluecode.tool.Result;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolTest {
    private final Duration oldTimeout = McpTool.callTimeout;

    @AfterEach
    void tearDown() {
        McpTool.callTimeout = oldTimeout;
    }

    @Test
    void adaptsNameSchemaDescriptionAndReadOnlyHint() {
        McpSchema.Tool remote = McpSchema.Tool.builder("echo")
                .description("")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .annotations(McpSchema.ToolAnnotations.builder().readOnlyHint(true).build())
                .build();

        Optional<McpTool> tool = McpTool.adaptTool("demo", remote, (name, args) -> null);

        assertTrue(tool.isPresent());
        assertEquals("mcp__demo__echo", tool.get().name());
        assertTrue(tool.get().description().contains("demo"));
        assertEquals("object", tool.get().schema().get("type"));
        assertTrue(tool.get().readOnly());
    }

    @Test
    void skipsIllegalToolNames() throws Exception {
        McpSchema.Tool remote = McpSchema.Tool.builder("bad.name")
                .inputSchema(Map.of("type", "object"))
                .build();

        Capture<Optional<McpTool>> capture = captureErr(() -> McpTool.adaptTool("demo", remote, (name, args) -> null));

        assertTrue(capture.value().isEmpty());
        assertTrue(capture.err().contains("name contains illegal characters"));
    }

    @Test
    void executeConcatenatesTextAndDropsNonTextOnce() throws Exception {
        McpSchema.CallToolResult remote = McpSchema.CallToolResult.builder()
                .addTextContent("one")
                .addContent(new McpSchema.ImageContent(null, "abc", "image/png"))
                .addTextContent("two")
                .isError(false)
                .build();
        McpTool tool = McpTool.adaptTool("demo", tool("mix"), (name, args) -> remote).orElseThrow();

        Capture<Result> first = captureErr(() -> tool.execute(Map.of("x", "y")));
        Capture<Result> second = captureErr(() -> tool.execute(Map.of()));

        assertFalse(first.value().isError());
        assertEquals("one\ntwo", first.value().content());
        assertTrue(first.err().contains("non-text content blocks"));
        assertEquals("", second.err());
    }

    @Test
    void executeMapsRemoteErrorFlag() {
        McpSchema.CallToolResult remote = McpSchema.CallToolResult.builder()
                .addTextContent("remote failed")
                .isError(true)
                .build();
        McpTool tool = McpTool.adaptTool("demo", tool("fail"), (name, args) -> remote).orElseThrow();

        Result result = tool.execute(Map.of());

        assertTrue(result.isError());
        assertEquals("remote failed", result.content());
    }

    @Test
    void executeConvertsExceptionsToErrorResult() {
        McpTool tool = McpTool.adaptTool("demo", tool("boom"), (name, args) -> {
            throw new IllegalStateException("broken transport");
        }).orElseThrow();

        Result result = tool.execute(Map.of());

        assertTrue(result.isError());
        assertTrue(result.content().contains("broken transport"));
    }

    @Test
    void executeTimesOut() {
        McpTool.callTimeout = Duration.ofMillis(100);
        McpTool tool = McpTool.adaptTool("demo", tool("slow"), (name, args) -> {
            Thread.sleep(5_000);
            return McpSchema.CallToolResult.builder().addTextContent("late").build();
        }).orElseThrow();

        Result result = tool.execute(Map.of());

        assertTrue(result.isError());
        assertTrue(result.content().contains("timeout"));
    }

    private McpSchema.Tool tool(String name) {
        return McpSchema.Tool.builder(name)
                .description("desc")
                .inputSchema(Map.of("type", "object"))
                .build();
    }

    private <T> Capture<T> captureErr(ThrowingSupplier<T> supplier) throws Exception {
        PrintStream oldErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return new Capture<>(supplier.get(), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(oldErr);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record Capture<T>(T value, String err) {
    }
}
