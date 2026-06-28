package com.bluecode.llm;

import com.bluecode.conversation.Message;

import java.util.List;
import java.util.Map;

public record Request(List<Message> messages, List<Map<String, Object>> tools, SystemPrompt system, String reminder) {
    public Request {
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        system = system == null ? new SystemPrompt("", "") : system;
        reminder = reminder == null ? "" : reminder;
    }
}
