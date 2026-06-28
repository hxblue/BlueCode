package com.bluecode.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpManagerTest {
    private final Duration oldConnectTimeout = McpManager.connectTimeout;
    private final Duration oldCloseTimeout = McpManager.closeTimeout;

    @AfterEach
    void tearDown() {
        McpManager.connectTimeout = oldConnectTimeout;
        McpManager.closeTimeout = oldCloseTimeout;
        McpManager.resetClientFactory();
    }

    @Test
    void emptyConfigStartsAndClosesImmediately() {
        McpManager manager = McpManager.start(McpConfig.empty(), "test");

        assertTrue(manager.tools().isEmpty());
        manager.close();
    }

    @Test
    void successfulServersRegisterToolsAndFailuresAreIsolated() throws Exception {
        McpManager.clientFactory = (name, config, version) -> {
            if ("bad".equals(name)) {
                throw new IllegalStateException("missing command");
            }
            return new StubClient(List.of(tool("echo")));
        };
        McpConfig config = new McpConfig(Map.of(
                "bad", stdio("missing"),
                "good", stdio("echo")));

        Capture<McpManager> capture = captureErr(() -> McpManager.start(config, "test"));

        assertEquals(List.of("mcp__good__echo"), capture.value().tools().stream().map(com.bluecode.tool.Tool::name).toList());
        assertTrue(capture.err().contains("connect server bad failed"));
        capture.value().close();
    }

    @Test
    void connectTimeoutSkipsServer() throws Exception {
        McpManager.connectTimeout = Duration.ofMillis(100);
        McpManager.clientFactory = (name, config, version) -> new StubClient(List.of(tool("late")), 5_000, 0);

        long started = System.nanoTime();
        Capture<McpManager> capture = captureErr(() -> McpManager.start(new McpConfig(Map.of("slow", stdio("slow"))), "test"));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(elapsedMillis < 1500);
        assertTrue(capture.value().tools().isEmpty());
        assertTrue(capture.err().contains("timeout"));
        capture.value().close();
    }

    @Test
    void closeReturnsAtFallbackDeadline() {
        McpManager.closeTimeout = Duration.ofMillis(100);
        McpManager.clientFactory = (name, config, version) -> new StubClient(List.of(tool("echo")), 0, 5_000);
        McpManager manager = McpManager.start(new McpConfig(Map.of("demo", stdio("echo"))), "test");

        long started = System.nanoTime();
        manager.close();
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(elapsedMillis < 1500);
    }

    @Test
    void mergeOsEnvAllowsServerEnvToOverrideHost() {
        String key = System.getenv().keySet().iterator().next();

        Map<String, String> merged = McpManager.mergeOsEnv(Map.of(key, "override"));

        assertEquals("override", merged.get(key));
    }

    private ServerConfig stdio(String command) {
        return new ServerConfig("stdio", command, List.of(), Map.of(), null, Map.of());
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

    private static final class StubClient implements McpManager.ManagedClient {
        private final List<McpSchema.Tool> tools;
        private final long initializeSleepMillis;
        private final long closeSleepMillis;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private StubClient(List<McpSchema.Tool> tools) {
            this(tools, 0, 0);
        }

        private StubClient(List<McpSchema.Tool> tools, long initializeSleepMillis, long closeSleepMillis) {
            this.tools = tools;
            this.initializeSleepMillis = initializeSleepMillis;
            this.closeSleepMillis = closeSleepMillis;
        }

        @Override
        public void initialize() {
            sleep(initializeSleepMillis);
        }

        @Override
        public List<McpSchema.Tool> listTools() {
            return tools;
        }

        @Override
        public boolean closeGracefully() {
            closeCalls.incrementAndGet();
            sleep(closeSleepMillis);
            return true;
        }

        @Override
        public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
            return McpSchema.CallToolResult.builder().addTextContent(name).build();
        }

        private void sleep(long millis) {
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
