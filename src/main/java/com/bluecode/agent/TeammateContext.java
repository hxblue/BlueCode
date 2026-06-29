package com.bluecode.agent;

import com.bluecode.team.BackendType;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public record TeammateContext(
        String teamName,
        String memberName,
        String agentId,
        BackendType backendType,
        Supplier<ReadUnreadView> readUnread,
        Consumer<List<Integer>> markRead) {
    public TeammateContext {
        teamName = teamName == null ? "" : teamName;
        memberName = memberName == null ? "" : memberName;
        agentId = agentId == null ? "" : agentId;
        backendType = backendType == null ? BackendType.IN_PROCESS : backendType;
    }

    public record IncomingMessage(
            String from,
            String type,
            long timestamp,
            String summary,
            String content,
            Map<String, Object> payload) {
        public IncomingMessage {
            from = from == null ? "" : from;
            type = type == null ? "" : type;
            summary = summary == null ? "" : summary;
            content = content == null ? "" : content;
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }
    }

    public record ReadUnreadView(List<Integer> indices, List<IncomingMessage> messages) {
        public ReadUnreadView {
            indices = indices == null ? List.of() : List.copyOf(indices);
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }
}
