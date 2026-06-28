package com.bluecode.conversation;

import com.bluecode.llm.ToolCall;
import com.bluecode.llm.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationTest {
    @Test
    void keepsMessagesInOrder() {
        ConversationManager conversation = new ConversationManager();

        conversation.addUserMessage("第一轮");
        conversation.addAssistantMessage("收到");
        conversation.addUserMessage("还记得吗？");

        List<Message> messages = conversation.getMessages();
        assertEquals(3, messages.size());
        assertEquals(Message.Role.USER, messages.get(0).role());
        assertEquals("收到", messages.get(1).content());
        assertEquals("还记得吗？", messages.get(2).content());
    }

    @Test
    void returnedHistoryIsImmutable() {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("hello");

        assertThrows(UnsupportedOperationException.class, () -> conversation.getMessages().add(new Message(Message.Role.USER, "x")));
    }

    @Test
    void storesToolCallsAndResults() {
        ConversationManager conversation = new ConversationManager();

        conversation.addAssistantToolCallMessage("我需要读文件", List.of(new ToolCall("call-1", "ReadFile", "{\"path\":\"a.txt\"}")));
        conversation.addToolResults(List.of(new ToolResult("call-1", "内容", false)));

        List<Message> messages = conversation.getMessages();
        assertEquals(Message.Role.ASSISTANT, messages.get(0).role());
        assertEquals(1, messages.get(0).toolCalls().size());
        assertEquals(Message.Role.TOOL, messages.get(1).role());
        assertEquals(1, messages.get(1).toolResults().size());
    }

    @Test
    void lastRoleReturnsTailRole() {
        ConversationManager conversation = new ConversationManager();

        assertTrue(conversation.lastRole().isEmpty());
        conversation.addUserMessage("hello");
        assertEquals(Message.Role.USER, conversation.lastRole().orElseThrow());
        conversation.addToolResults(List.of(new ToolResult("call-1", "内容", false)));
        assertEquals(Message.Role.TOOL, conversation.lastRole().orElseThrow());
        conversation.addAssistantMessage("done");
        assertEquals(Message.Role.ASSISTANT, conversation.lastRole().orElseThrow());
    }

    @Test
    void replaceMessagesDeepCopiesInput() {
        ConversationManager conversation = new ConversationManager();
        List<Message> source = new java.util.ArrayList<>();
        source.add(new Message(Message.Role.USER, "原文"));
        source.add(new Message(
                Message.Role.ASSISTANT,
                "调用",
                List.of(new ToolCall("call-1", "ReadFile", "{}")),
                List.of()));

        conversation.replaceMessages(source);
        source.clear();

        List<Message> messages = conversation.getMessages();
        assertEquals(2, messages.size());
        assertEquals("原文", messages.getFirst().content());
        assertEquals(1, messages.get(1).toolCalls().size());
    }

    @Test
    void replaceMessagesAcceptsNullAsEmpty() {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("hello");

        conversation.replaceMessages(null);

        assertTrue(conversation.getMessages().isEmpty());
    }

    @Test
    void callbacksFireAfterAppendAndReplace() {
        AtomicInteger appends = new AtomicInteger();
        AtomicReference<List<Message>> replaced = new AtomicReference<>();
        ConversationManager conversation = new ConversationManager(
                message -> appends.incrementAndGet(),
                replaced::set);

        conversation.addUserMessage("hello");
        conversation.addAssistantMessage("hi");
        conversation.replaceMessages(List.of(new Message(Message.Role.USER, "summary")));

        assertEquals(2, appends.get());
        assertEquals(1, replaced.get().size());
        assertEquals("summary", replaced.get().getFirst().content());
    }

    @Test
    void fromMessagesDeepCopiesAndKeepsCallbacks() {
        AtomicInteger appends = new AtomicInteger();
        List<Message> source = new java.util.ArrayList<>();
        source.add(new Message(Message.Role.USER, "old"));

        ConversationManager conversation = ConversationManager.fromMessages(source, ignored -> appends.incrementAndGet(), null);
        source.clear();
        conversation.addAssistantMessage("new");

        assertEquals(2, conversation.size());
        assertEquals(1, appends.get());
        assertEquals("old", conversation.getMessages().getFirst().content());
    }
}
