package com.bluecode.llm;

// 协议无关的工具结果，通过 toolCallId 与模型工具调用配对。
public record ToolResult(String toolCallId, String content, boolean isError) {
    public ToolResult {
        toolCallId = toolCallId == null ? "" : toolCallId;
        content = content == null ? "" : content;
    }
}
