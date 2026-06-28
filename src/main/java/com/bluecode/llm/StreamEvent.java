package com.bluecode.llm;

public sealed interface StreamEvent permits StreamEvent.TextDelta, StreamEvent.ThinkingDelta, StreamEvent.ToolCallStart,
        StreamEvent.ToolCallDelta, StreamEvent.ToolCallComplete, StreamEvent.UsageEvent, StreamEvent.StreamEnd,
        StreamEvent.Error {
    record TextDelta(String text) implements StreamEvent {
    }

    record ThinkingDelta(String text) implements StreamEvent {
    }

    record ToolCallStart(String toolCallId, String toolName) implements StreamEvent {
    }

    record ToolCallDelta(String toolCallId, String delta) implements StreamEvent {
    }

    record ToolCallComplete(String toolCallId, String toolName, String arguments) implements StreamEvent {
    }

    // 一轮流式响应结束前上报 token 用量；部分兼容端点可能不上报。
    record UsageEvent(Usage usage) implements StreamEvent {
    }

    record StreamEnd(String stopReason, int inputTokens, int outputTokens) implements StreamEvent {
    }

    record Error(String message, Throwable cause) implements StreamEvent {
        public Error(String message) {
            this(message, null);
        }
    }
}
