package com.bluecode.mcp;

import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

final class McpTool implements Tool {
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final ConcurrentHashMap<String, Boolean> NON_TEXT_WARNED = new ConcurrentHashMap<>();

    static volatile Duration callTimeout = Duration.ofSeconds(30);

    private final String fullName;
    private final String remoteName;
    private final String description;
    private final Map<String, Object> schema;
    private final boolean readOnly;
    private final CallerSession session;

    private McpTool(String fullName, String remoteName, String description, Map<String, Object> schema,
                    boolean readOnly, CallerSession session) {
        this.fullName = fullName;
        this.remoteName = remoteName;
        this.description = description;
        this.schema = Map.copyOf(schema);
        this.readOnly = readOnly;
        this.session = session;
    }

    static Optional<McpTool> adaptTool(String serverName, McpSchema.Tool tool, CallerSession session) {
        String fullName = "mcp__" + serverName + "__" + tool.name();
        if (!VALID_NAME.matcher(fullName).matches()) {
            System.err.printf("[mcp] warn: skip tool %s: name contains illegal characters%n", fullName);
            return Optional.empty();
        }
        String description = tool.description();
        if (description == null || description.isBlank()) {
            description = "来自 MCP server " + serverName + " 的工具 " + tool.name();
        }
        Map<String, Object> schema = tool.inputSchema();
        if (schema == null || schema.isEmpty()) {
            schema = Map.of("type", "object");
        } else {
            schema = new LinkedHashMap<>(schema);
        }
        boolean readOnly = tool.annotations() != null && Boolean.TRUE.equals(tool.annotations().readOnlyHint());
        return Optional.of(new McpTool(fullName, tool.name(), description, schema, readOnly, session));
    }

    @Override
    public String name() {
        return fullName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> schema() {
        return schema;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        Map<String, Object> arguments = args == null ? Map.of() : new LinkedHashMap<>(args);
        FutureTask<McpSchema.CallToolResult> task = new FutureTask<>(() -> session.callTool(remoteName, arguments));
        Thread worker = Thread.startVirtualThread(task);
        McpSchema.CallToolResult result;
        try {
            result = task.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            worker.interrupt();
            return Result.error("MCP 工具调用失败: timeout after " + callTimeout.toSeconds() + " seconds");
        } catch (InterruptedException e) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            return Result.error("MCP 工具调用失败: interrupted");
        } catch (Exception e) {
            return Result.error("MCP 工具调用失败: " + messageOf(e));
        }
        return toToolResult(result);
    }

    private Result toToolResult(McpSchema.CallToolResult result) {
        if (result == null) {
            return Result.error("MCP 工具调用失败: empty result");
        }
        List<McpSchema.Content> content = result.content() == null ? List.of() : result.content();
        StringBuilder text = new StringBuilder();
        boolean dropped = false;
        for (McpSchema.Content block : content) {
            if (block instanceof McpSchema.TextContent textContent) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textContent.text() == null ? "" : textContent.text());
            } else {
                dropped = true;
            }
        }
        if (dropped && NON_TEXT_WARNED.putIfAbsent(fullName, Boolean.TRUE) == null) {
            System.err.printf("[mcp] warn: tool %s returned non-text content blocks (dropped)%n", fullName);
        }
        return new Result(text.toString(), Boolean.TRUE.equals(result.isError()));
    }

    private String messageOf(Exception e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}

interface CallerSession {
    McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) throws Exception;
}
