package com.bluecode.llm;

import com.bluecode.config.ProviderConfig;
import com.bluecode.conversation.Message;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class AnthropicClient implements LlmClient {
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final ProviderConfig config;

    public AnthropicClient(ProviderConfig config) {
        this.config = config;
    }

    public AnthropicClient(ProviderConfig config, String ignoredSystemPrompt) {
        this(config);
    }

    @Override
    public BlockingQueue<StreamEvent> stream(Request request) {
        BlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
        Thread.startVirtualThread(() -> runStream(request, queue));
        return queue;
    }

    @Override
    public String model() {
        return config.getModel();
    }

    private void runStream(Request request, BlockingQueue<StreamEvent> queue) {
        try {
            boolean enableThinking = config.isThinking() && !hasToolMessages(request.messages());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.getModel());
            List<Map<String, Object>> systemBlocks = buildSystemBlocks(request.system());
            if (!systemBlocks.isEmpty()) {
                body.put("system", systemBlocks);
            }
            body.put("max_tokens", enableThinking ? 4096 : 2048);
            body.put("stream", true);
            body.put("messages", buildMessages(request.messages(), request.reminder()));
            if (!request.tools().isEmpty()) {
                body.put("tools", toAnthropicTools(request.tools()));
            }
            if (enableThinking) {
                body.put("thinking", Map.of("type", "enabled", "budget_tokens", 1024));
            }

            HttpRequest httpRequest = JsonHttpStreamer.postJson(normalizeEndpoint(config.getBaseUrl()), JsonHttpStreamer.toJson(body))
                    .header("x-api-key", config.getApiKey())
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .build();

            HttpResponse<InputStream> response = JsonHttpStreamer.client()
                    .send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseBody = JsonHttpStreamer.readBody(response.body());
                String message = "HTTP " + response.statusCode() + ": " + JsonHttpStreamer.extractErrorMessage(responseBody);
                queue.offer(new StreamEvent.Error(message, promptTooLongCause(response.statusCode(), responseBody, message)));
                return;
            }
            Map<Integer, AnthropicToolBuffer> toolBuffers = new LinkedHashMap<>();
            AtomicBoolean ended = new AtomicBoolean(false);
            AtomicReference<String> stopReason = new AtomicReference<>("stop");
            AtomicLong inputTokens = new AtomicLong();
            AtomicLong outputTokens = new AtomicLong();
            AtomicLong cacheWrite = new AtomicLong();
            AtomicLong cacheRead = new AtomicLong();
            JsonHttpStreamer.readSseStream(response.body(),
                    data -> handleData(data, queue, toolBuffers, ended, stopReason, inputTokens, outputTokens,
                            cacheWrite, cacheRead));
            if (!ended.get()) {
                flushToolCalls(toolBuffers, queue);
                emitUsage(queue, inputTokens.get(), outputTokens.get(), cacheWrite.get(), cacheRead.get());
                queue.offer(new StreamEvent.StreamEnd(stopReason.get(), 0, 0));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queue.offer(new StreamEvent.Error("请求已中断"));
        } catch (Exception e) {
            queue.offer(new StreamEvent.Error("Anthropic 请求失败: " + e.getMessage()));
        }
    }

    static List<Map<String, Object>> buildSystemBlocks(SystemPrompt system) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (system != null && !system.stable().isBlank()) {
            Map<String, Object> stable = new LinkedHashMap<>();
            stable.put("type", "text");
            stable.put("text", system.stable());
            stable.put("cache_control", Map.of("type", "ephemeral"));
            blocks.add(stable);
        }
        if (system != null && !system.environment().isBlank()) {
            Map<String, Object> environment = new LinkedHashMap<>();
            environment.put("type", "text");
            environment.put("text", system.environment());
            blocks.add(environment);
        }
        return blocks;
    }

    private List<Map<String, Object>> buildMessages(List<Message> history, String reminder) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message message : history) {
            switch (message.role()) {
                case USER -> messages.add(buildUserTextMessage(message.content()));
                case ASSISTANT -> messages.add(buildAssistantMessage(message));
                case TOOL -> messages.add(buildToolResultMessage(message));
            }
        }
        appendReminder(messages, reminder);
        return messages;
    }

    private Map<String, Object> buildUserTextMessage(String text) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", text);
        return message;
    }

    private Map<String, Object> buildAssistantMessage(Message message) {
        if (message.toolCalls().isEmpty()) {
            return Map.of("role", "assistant", "content", message.content());
        }
        List<Map<String, Object>> content = new ArrayList<>();
        if (!message.content().isBlank()) {
            content.add(Map.of("type", "text", "text", message.content()));
        }
        for (ToolCall call : message.toolCalls()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_use");
            block.put("id", call.id());
            block.put("name", call.name());
            block.put("input", parseArguments(call.arguments()));
            content.add(block);
        }
        return Map.of("role", "assistant", "content", content);
    }

    private Map<String, Object> buildToolResultMessage(Message message) {
        List<Map<String, Object>> content = new ArrayList<>();
        for (ToolResult result : message.toolResults()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put("type", "tool_result");
            block.put("tool_use_id", result.toolCallId());
            block.put("content", result.content());
            block.put("is_error", result.isError());
            content.add(block);
        }
        return Map.of("role", "user", "content", content);
    }

    private void appendReminder(List<Map<String, Object>> messages, String reminder) {
        if (reminder == null || reminder.isBlank()) {
            return;
        }
        if (messages.isEmpty() || !"user".equals(messages.getLast().get("role"))) {
            messages.add(buildUserTextMessage(reminder));
            return;
        }

        Map<String, Object> last = messages.removeLast();
        List<Object> content = new ArrayList<>();
        Object original = last.get("content");
        if (original instanceof String text && !text.isBlank()) {
            content.add(Map.of("type", "text", "text", text));
        } else if (original instanceof List<?> list) {
            content.addAll(list);
        }
        content.add(Map.of("type", "text", "text", reminder));

        Map<String, Object> replacement = new LinkedHashMap<>(last);
        replacement.put("content", content);
        messages.add(replacement);
    }

    private List<Map<String, Object>> toAnthropicTools(List<Map<String, Object>> tools) {
        List<Map<String, Object>> converted = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.get("name"));
            item.put("description", tool.get("description"));
            item.put("input_schema", tool.get("input_schema"));
            converted.add(item);
        }
        return converted;
    }

    private Object parseArguments(String arguments) {
        try {
            return JsonHttpStreamer.MAPPER.readValue(arguments == null || arguments.isBlank() ? "{}" : arguments, Object.class);
        } catch (IOException e) {
            return Map.of();
        }
    }

    private boolean hasToolMessages(List<Message> history) {
        return history.stream()
                .anyMatch(message -> message.role() == Message.Role.TOOL || !message.toolCalls().isEmpty());
    }

    private void handleData(String data, BlockingQueue<StreamEvent> queue, Map<Integer, AnthropicToolBuffer> toolBuffers,
                            AtomicBoolean ended, AtomicReference<String> stopReason, AtomicLong inputTokens,
                            AtomicLong outputTokens, AtomicLong cacheWrite, AtomicLong cacheRead) throws IOException {
        JsonNode root = JsonHttpStreamer.MAPPER.readTree(data);
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = JsonHttpStreamer.extractErrorMessage(data);
            queue.offer(new StreamEvent.Error(message, promptTooLongCause(400, data, message)));
            return;
        }

        String type = root.path("type").asText();
        if ("message_start".equals(type)) {
            JsonNode usage = root.path("message").path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                updateUsage(usage, inputTokens, outputTokens, cacheWrite, cacheRead);
            }
        } else if ("content_block_start".equals(type)) {
            JsonNode block = root.path("content_block");
            if ("tool_use".equals(block.path("type").asText())) {
                int index = root.path("index").asInt(toolBuffers.size());
                AnthropicToolBuffer buffer = toolBuffers.computeIfAbsent(index, ignored -> new AnthropicToolBuffer());
                buffer.id = block.path("id").asText();
                buffer.name = block.path("name").asText();
                queue.offer(new StreamEvent.ToolCallStart(buffer.id(), buffer.name));
            }
        } else if ("content_block_delta".equals(type)) {
            int index = root.path("index").asInt(-1);
            JsonNode delta = root.path("delta");
            String deltaType = delta.path("type").asText();
            if ("text_delta".equals(deltaType) && delta.path("text").isTextual()) {
                queue.offer(new StreamEvent.TextDelta(delta.path("text").asText()));
            } else if (deltaType.contains("thinking") && delta.path("thinking").isTextual()) {
                queue.offer(new StreamEvent.ThinkingDelta(delta.path("thinking").asText()));
            } else if ("input_json_delta".equals(deltaType) && delta.path("partial_json").isTextual() && index >= 0) {
                AnthropicToolBuffer buffer = toolBuffers.computeIfAbsent(index, ignored -> new AnthropicToolBuffer());
                String fragment = delta.path("partial_json").asText();
                buffer.arguments.append(fragment);
                queue.offer(new StreamEvent.ToolCallDelta(buffer.id(), fragment));
            }
        } else if ("content_block_stop".equals(type)) {
            int index = root.path("index").asInt(-1);
            AnthropicToolBuffer buffer = toolBuffers.get(index);
            if (buffer != null && !buffer.completed) {
                queue.offer(new StreamEvent.ToolCallComplete(buffer.id(), buffer.name(), normalizeArguments(buffer.arguments.toString())));
                buffer.completed = true;
            }
        } else if ("message_delta".equals(type)) {
            JsonNode reason = root.path("delta").path("stop_reason");
            if (reason.isTextual() && !"null".equals(reason.asText())) {
                stopReason.set(reason.asText());
            }
            JsonNode usage = root.path("usage");
            if (!usage.isMissingNode() && !usage.isNull()) {
                updateUsage(usage, inputTokens, outputTokens, cacheWrite, cacheRead);
            }
        } else if ("message_stop".equals(type)) {
            flushToolCalls(toolBuffers, queue);
            emitUsage(queue, inputTokens.get(), outputTokens.get(), cacheWrite.get(), cacheRead.get());
            queue.offer(new StreamEvent.StreamEnd(stopReason.get(), 0, 0));
            ended.set(true);
        }
    }

    private void updateUsage(JsonNode usage, AtomicLong inputTokens, AtomicLong outputTokens, AtomicLong cacheWrite,
                             AtomicLong cacheRead) {
        inputTokens.set(usage.path("input_tokens").asLong(inputTokens.get()));
        outputTokens.set(usage.path("output_tokens").asLong(outputTokens.get()));
        cacheWrite.set(usage.path("cache_creation_input_tokens").asLong(cacheWrite.get()));
        cacheRead.set(usage.path("cache_read_input_tokens").asLong(cacheRead.get()));
    }

    private void flushToolCalls(Map<Integer, AnthropicToolBuffer> toolBuffers, BlockingQueue<StreamEvent> queue) {
        for (AnthropicToolBuffer buffer : toolBuffers.values()) {
            if (!buffer.completed && !buffer.name().isBlank()) {
                queue.offer(new StreamEvent.ToolCallComplete(buffer.id(), buffer.name(), normalizeArguments(buffer.arguments.toString())));
                buffer.completed = true;
            }
        }
    }

    private void emitUsage(BlockingQueue<StreamEvent> queue, long input, long output, long cacheWrite, long cacheRead) {
        if (input > 0 || output > 0 || cacheWrite > 0 || cacheRead > 0) {
            queue.offer(new StreamEvent.UsageEvent(new Usage(input, output, cacheWrite, cacheRead)));
        }
    }

    private String normalizeArguments(String arguments) {
        return arguments == null || arguments.isBlank() ? "{}" : arguments;
    }

    private String normalizeEndpoint(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl.trim();
        if (base.endsWith("/v1/messages")) {
            return base;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
    }

    private Throwable promptTooLongCause(int statusCode, String body, String message) {
        String lower = ((body == null ? "" : body) + " " + (message == null ? "" : message)).toLowerCase();
        boolean matched = statusCode == 400 && (lower.contains("prompt is too long")
                || lower.contains("context_length")
                || lower.contains("context length")
                || lower.contains("context window")
                || lower.contains("maximum context")
                || lower.contains("too many tokens"));
        return matched ? new PromptTooLongException(new IOException(message)) : null;
    }

    private static final class AnthropicToolBuffer {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
        private boolean completed;

        private String id() {
            return id == null || id.isBlank() ? "toolu_" + Math.abs(System.identityHashCode(this)) : id;
        }

        private String name() {
            return name == null ? "" : name;
        }
    }
}
