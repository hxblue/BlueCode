package com.bluecode.teams;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record Message(
        @JsonProperty("from") String from,
        @JsonProperty("to") String to,
        @JsonProperty("type") MessageType type,
        @JsonProperty("summary") String summary,
        @JsonProperty("content") String content,
        @JsonProperty("payload") Map<String, Object> payload,
        @JsonProperty("timestamp") long timestamp,
        @JsonProperty("read") boolean read) {
    public Message {
        from = from == null ? "" : from;
        to = to == null ? "" : to;
        type = type == null ? MessageType.TEXT : type;
        summary = summary == null ? "" : summary;
        content = content == null ? "" : content;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public Message withTimestamp(long value) {
        return new Message(from, to, type, summary, content, payload, value, read);
    }

    public Message withRead(boolean value) {
        return new Message(from, to, type, summary, content, payload, timestamp, value);
    }
}
