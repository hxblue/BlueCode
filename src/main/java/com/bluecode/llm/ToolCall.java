package com.bluecode.llm;

// 协议无关的工具调用，承载模型请求的工具名与 JSON 参数。
public record ToolCall(String id, String name, String arguments) {
    public ToolCall {
        id = id == null || id.isBlank() ? "tool-" + System.nanoTime() : id;
        name = name == null ? "" : name;
        arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
    }
}
