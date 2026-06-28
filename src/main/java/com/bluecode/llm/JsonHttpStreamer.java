package com.bluecode.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

final class JsonHttpStreamer {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonHttpStreamer() {
    }

    static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    static HttpRequest.Builder postJson(String url, String json) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("content-type", "application/json")
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json));
    }

    static String toJson(Map<String, Object> body) {
        try {
            return MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("请求体序列化失败", e);
        }
    }

    static void ensureSuccess(HttpResponse<String> response, BlockingQueue<StreamEvent> queue) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        String message = extractErrorMessage(response.body());
        queue.offer(new StreamEvent.Error("HTTP " + response.statusCode() + ": " + message));
    }

    static String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "服务端没有返回错误详情";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode error = root.path("error");
            if (error.isTextual()) {
                return error.asText();
            }
            JsonNode message = error.path("message");
            if (message.isTextual()) {
                return message.asText();
            }
            JsonNode rootMessage = root.path("message");
            if (rootMessage.isTextual()) {
                return rootMessage.asText();
            }
        } catch (JsonProcessingException ignored) {
            // body 不是 JSON 时直接展示截断后的原文。
        }
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }

    static String readBody(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    static void readSseLines(String body, SseDataHandler handler) throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
            readSse(reader, handler);
        }
    }

    static void readSseStream(InputStream body, SseDataHandler handler) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            readSse(reader, handler);
        }
    }

    private static void readSse(BufferedReader reader, SseDataHandler handler) throws IOException {
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                if (!data.isEmpty()) {
                    handler.onData(data.toString());
                    data.setLength(0);
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (!data.isEmpty()) {
            handler.onData(data.toString());
        }
    }

    interface SseDataHandler {
        void onData(String data) throws IOException;
    }
}
