package com.bluecode.agent;

import com.bluecode.conversation.Message;
import com.bluecode.llm.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForkTest {
    @Test
    void appendsBoilerplateAndDanglingToolResult() {
        List<Message> forked = Fork.buildForkedMessages(List.of(
                new Message(Message.Role.USER, "hello"),
                new Message(Message.Role.ASSISTANT, "", List.of(new ToolCall("call-1", "ReadFile", "{}")), List.of())
        ), "do work");

        assertEquals(Message.Role.TOOL, forked.get(2).role());
        assertEquals("call-1", forked.get(2).toolResults().getFirst().toolCallId());
        assertEquals(Message.Role.USER, forked.getLast().role());
        assertTrue(forked.getLast().content().contains(Fork.FORK_BOILERPLATE_TAG));
        assertTrue(Fork.isForkContext(forked));
    }
}
