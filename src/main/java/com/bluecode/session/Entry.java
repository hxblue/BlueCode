package com.bluecode.session;

import com.bluecode.conversation.Message;
import com.bluecode.llm.ToolCall;
import com.bluecode.llm.ToolResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Entry(
        String type,
        String role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_results") List<ToolResult> toolResults,
        long ts,
        String model) {

    public Entry {
        toolCalls = toolCalls == null || toolCalls.isEmpty() ? null : List.copyOf(toolCalls);
        toolResults = toolResults == null || toolResults.isEmpty() ? null : List.copyOf(toolResults);
    }

    public static Entry compact(long ts) {
        return new Entry("compact", null, null, null, null, ts, null);
    }

    public static Entry fromMessage(Message message, long ts, String model) {
        return new Entry(
                null,
                message.role().name().toLowerCase(Locale.ROOT),
                message.content(),
                message.toolCalls(),
                message.toolResults(),
                ts,
                model == null || model.isBlank() ? null : model);
    }

    public Message toMessage() {
        if (role == null) {
            return null;
        }
        Message.Role parsed = switch (role) {
            case "user" -> Message.Role.USER;
            case "assistant" -> Message.Role.ASSISTANT;
            case "tool" -> Message.Role.TOOL;
            default -> null;
        };
        if (parsed == null) {
            return null;
        }
        return new Message(
                parsed,
                content,
                toolCalls == null ? List.of() : toolCalls,
                toolResults == null ? List.of() : toolResults);
    }
}
