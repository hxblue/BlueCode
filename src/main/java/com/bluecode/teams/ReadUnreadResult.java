package com.bluecode.teams;

import java.util.List;

public record ReadUnreadResult(List<Integer> indices, List<Message> messages) {
    public ReadUnreadResult {
        indices = indices == null ? List.of() : List.copyOf(indices);
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
