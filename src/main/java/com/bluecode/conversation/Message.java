package com.bluecode.conversation;

import com.bluecode.llm.ToolCall;
import com.bluecode.llm.ToolResult;

import java.util.List;

public record Message(Role role, String content, List<ToolCall> toolCalls, List<ToolResult> toolResults) {
    public enum Role {
        USER,
        ASSISTANT,
        TOOL
    }

    public Message(Role role, String content) {
        this(role, content, List.of(), List.of());
    }

    public Message {
        if (role == null) {
            throw new IllegalArgumentException("role 不能为空");
        }
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        toolResults = toolResults == null ? List.of() : List.copyOf(toolResults);
    }
}
