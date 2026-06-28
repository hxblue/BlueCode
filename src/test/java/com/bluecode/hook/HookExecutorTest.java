package com.bluecode.hook;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookExecutorTest {
    @Test
    void shellExitCodeTwoBlocksWithReason() {
        HookRule rule = rule("block", Event.PRE_TOOL_USE,
                new Action.Shell("echo blocked by hook >&2; exit 2"), Duration.ofSeconds(5));

        ExecutionResult result = new HookExecutor().run(rule, new Payload(Map.of()), true);

        assertTrue(result.blocked());
        assertTrue(result.reason().contains("blocked by hook"));
    }

    @Test
    void shellExitOneIsFailureButNotBlock() {
        HookRule rule = rule("fail", Event.PRE_TOOL_USE, new Action.Shell("exit 1"), Duration.ofSeconds(5));

        ExecutionResult result = new HookExecutor().run(rule, new Payload(Map.of()), true);

        assertFalse(result.blocked());
        assertNotNull(result.error());
    }

    @Test
    void promptReturnsInjectedText() {
        HookRule rule = rule("prompt", Event.SESSION_START, new Action.Prompt("用中文回复"), Duration.ofSeconds(1));

        ExecutionResult result = new HookExecutor().run(rule, new Payload(Map.of()), false);

        assertEquals("用中文回复", result.prompt());
    }

    @Test
    void httpCanBlockAndRenderTemplate() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/check", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] response = "{\"decision\":\"block\",\"reason\":\"network policy\"}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/check";
            HookRule rule = rule("http", Event.PRE_TOOL_USE,
                    new Action.Http(url, "POST", Map.of(), "{\"event\":\"${event}\"}"),
                    Duration.ofSeconds(5));

            ExecutionResult result = new HookExecutor().run(rule, new Payload(Map.of("event", "PreToolUse")), true);

            assertTrue(result.blocked());
            assertEquals("network policy", result.reason());
            assertEquals("{\"event\":\"PreToolUse\"}", body.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void subagentOnlyLogsPlaceholder() {
        HookRule rule = rule("sub", Event.SESSION_START, new Action.Subagent("foo", "test"), Duration.ofSeconds(1));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream oldErr = System.err;
        System.setErr(new PrintStream(buffer));
        try {
            ExecutionResult result = new HookExecutor().run(rule, new Payload(Map.of()), false);
            assertFalse(result.blocked());
        } finally {
            System.setErr(oldErr);
        }
        assertTrue(buffer.toString().contains("[hook subagent] not yet implemented, skipped: sub"));
    }

    private HookRule rule(String name, Event event, Action action, Duration timeout) {
        return new HookRule(name, event, null, action, false, false, timeout, "test");
    }
}
