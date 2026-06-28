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

public class OpenAiClient implements LlmClient {
    private final ProviderConfig config;
    private final boolean compatMode;

    public OpenAiClient(ProviderConfig config, boolean compatMode) {
        this.config = config;
        this.compatMode = compatMode;
    }

    public OpenAiClient(ProviderConfig config, String ignoredSystemPrompt, boolean compatMode) {
        this(config, compatMode);
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
            String endpoint = normalizeEndpoint(config.getBaseUrl());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.getModel());
            body.put("stream", true);
            body.put("stream_options", Map.of("include_usage", true));
            body.put("messages", buildMessages(request));
            if (!request.tools().isEmpty()) {
                body.put("tools", toOpenAiTools(request.tools()));
                body.put("tool_choice", "auto");
            }
            if (isDeepSeekEndpoint()) {
                body.put("thinking", Map.of("type", config.isThinking() ? "enabled" : "disabled"));
            }

            HttpRequest httpRequest = JsonHttpStreamer.postJson(endpoint, JsonHttpStreamer.toJson(body))
                    .header("authorization", "Bearer " + config.getApiKey())
                    .build();

            HttpResponse<InputStream> response = JsonHttpStreamer.client()
                    .send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String responseBody = JsonHttpStreamer.readBody(response.body());
                String message = "HTTP " + response.statusCode() + ": " + JsonHttpStreamer.extractErrorMessage(responseBody);
                queue.offer(new StreamEvent.Error(message, promptTooLongCause(response.statusCode(), responseBody, message)));
                return;
            }
            Map<Integer, OpenAiToolBuffer> toolBuffers = new LinkedHashMap<>();
            AtomicBoolean ended = new AtomicBoolean(false);
            JsonHttpStreamer.readSseStream(response.body(), data -> handleData(data, queue, toolBuffers, ended));
            if (!ended.get()) {
                flushToolCalls(toolBuffers, queue);
                queue.offer(new StreamEvent.StreamEnd("stop", 0, 0));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            queue.offer(new StreamEvent.Error("请求已中断"));
        } catch (Exception e) {
            queue.offer(new StreamEvent.Error("OpenAI 请求失败: " + e.getMessage()));
        }
    }

    private List<Map<String, Object>> buildMessages(Request request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        String systemText = renderSystem(request.system());
        if (!systemText.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemText));
        }
        for (Message message : request.messages()) {
            switch (message.role()) {
                case USER -> messages.add(Map.of("role", "user", "content", message.content()));
                case ASSISTANT -> messages.add(buildAssistantMessage(message));
                case TOOL -> {
                    for (ToolResult result : message.toolResults()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("role", "tool");
                        item.put("tool_call_id", result.toolCallId());
                        item.put("content", result.content());
                        messages.add(item);
                    }
                }
            }
        }
        if (!request.reminder().isBlank()) {
            messages.add(Map.of("role", "user", "content", request.reminder()));
        }
        return messages;
    }

    private String renderSystem(SystemPrompt system) {
        if (system == null) {
            return "";
        }
        if (system.environment().isBlank()) {
            return system.stable();
        }
        if (system.stable().isBlank()) {
            return system.environment();
        }
        return system.stable() + "\n\n" + system.environment();
    }

    private Map<String, Object> buildAssistantMessage(Message message) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", message.content());
        if (!message.toolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (ToolCall call : message.toolCalls()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.name());
                function.put("arguments", normalizeArguments(call.arguments()));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", call.id());
                item.put("type", "function");
                item.put("function", function);
                calls.add(item);
            }
            assistant.put("tool_calls", calls);
        }
        return assistant;
    }

    private List<Map<String, Object>> toOpenAiTools(List<Map<String, Object>> tools) {
        List<Map<String, Object>> converted = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.get("name"));
            function.put("description", tool.get("description"));
            function.put("parameters", tool.get("input_schema"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "function");
            item.put("function", function);
            converted.add(item);
        }
        return converted;
    }

    private void handleData(String data, BlockingQueue<StreamEvent> queue, Map<Integer, OpenAiToolBuffer> toolBuffers,
                            AtomicBoolean ended) throws IOException {
        if ("[DONE]".equals(data)) {
            return;
        }
        JsonNode root = JsonHttpStreamer.MAPPER.readTree(data);
        JsonNode error = root.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            String message = JsonHttpStreamer.extractErrorMessage(data);
            queue.offer(new StreamEvent.Error(message, promptTooLongCause(400, data, message)));
            return;
        }
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
            long input = usage.path("prompt_tokens").asLong(0);
            long output = usage.path("completion_tokens").asLong(0);
            long cacheRead = usage.path("prompt_tokens_details").path("cached_tokens").asLong(0);
            if (input > 0 || output > 0 || cacheRead > 0) {
                queue.offer(new StreamEvent.UsageEvent(new Usage(input, output, 0, cacheRead)));
            }
        }
        for (JsonNode choice : root.path("choices")) {
            JsonNode delta = choice.path("delta");
            JsonNode content = delta.path("content");
            if (content.isTextual() && !content.asText().isEmpty()) {
                queue.offer(new StreamEvent.TextDelta(content.asText()));
            }
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode toolCall : toolCalls) {
                    int index = toolCall.path("index").isInt() ? toolCall.path("index").asInt() : toolBuffers.size();
                    OpenAiToolBuffer buffer = toolBuffers.computeIfAbsent(index, ignored -> new OpenAiToolBuffer());
                    if (toolCall.path("id").isTextual()) {
                        buffer.id = toolCall.path("id").asText();
                    }
                    JsonNode function = toolCall.path("function");
                    if (function.path("name").isTextual()) {
                        buffer.name = function.path("name").asText();
                        if (!buffer.started) {
                            queue.offer(new StreamEvent.ToolCallStart(buffer.id(), buffer.name));
                            buffer.started = true;
                        }
                    }
                    if (function.path("arguments").isTextual()) {
                        String fragment = function.path("arguments").asText();
                        buffer.arguments.append(fragment);
                        queue.offer(new StreamEvent.ToolCallDelta(buffer.id(), fragment));
                    }
                }
            }
            JsonNode finishReason = choice.path("finish_reason");
            if (finishReason.isTextual() && !"null".equals(finishReason.asText())) {
                if ("tool_calls".equals(finishReason.asText())) {
                    flushToolCalls(toolBuffers, queue);
                }
                queue.offer(new StreamEvent.StreamEnd(finishReason.asText(), 0, 0));
                ended.set(true);
            }
        }
    }

    private void flushToolCalls(Map<Integer, OpenAiToolBuffer> toolBuffers, BlockingQueue<StreamEvent> queue) {
        for (OpenAiToolBuffer buffer : toolBuffers.values()) {
            if (!buffer.completed && !buffer.name().isBlank()) {
                queue.offer(new StreamEvent.ToolCallComplete(buffer.id(), buffer.name(), normalizeArguments(buffer.arguments.toString())));
                buffer.completed = true;
            }
        }
    }

    private String normalizeArguments(String arguments) {
        return arguments == null || arguments.isBlank() ? "{}" : arguments;
    }

    private String normalizeEndpoint(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl.trim();
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String suffix = compatMode || base.endsWith("/v1") ? "/chat/completions" : "/v1/chat/completions";
        return base + suffix;
    }

    private boolean isDeepSeekEndpoint() {
        String baseUrl = config.getBaseUrl();
        return baseUrl != null && baseUrl.toLowerCase().contains("deepseek.com");
    }

    private Throwable promptTooLongCause(int statusCode, String body, String message) {
        String lower = ((body == null ? "" : body) + " " + (message == null ? "" : message)).toLowerCase();
        boolean matched = statusCode == 400 && (lower.contains("context_length_exceeded")
                || lower.contains("context_length")
                || lower.contains("context length")
                || lower.contains("maximum context")
                || lower.contains("too many tokens"));
        return matched ? new PromptTooLongException(new IOException(message)) : null;
    }

    private static final class OpenAiToolBuffer {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
        private boolean started;
        private boolean completed;

        private String id() {
            return id.isBlank() ? "call_" + Math.abs(System.identityHashCode(this)) : id;
        }

        private String name() {
            return name == null ? "" : name;
        }
    }
}
