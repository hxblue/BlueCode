package com.bluecode.conversation;

import com.bluecode.llm.ToolCall;
import com.bluecode.llm.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public class ConversationManager {
    private final List<Message> messages = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Consumer<Message> onAppend;
    private final Consumer<List<Message>> onReplace;

    public ConversationManager() {
        this(null, null);
    }

    public ConversationManager(Consumer<Message> onAppend, Consumer<List<Message>> onReplace) {
        this.onAppend = onAppend;
        this.onReplace = onReplace;
    }

    public static ConversationManager fromMessages(
            List<Message> source,
            Consumer<Message> onAppend,
            Consumer<List<Message>> onReplace) {
        ConversationManager conversation = new ConversationManager(onAppend, onReplace);
        conversation.messages.addAll(copyMessages(source == null ? List.of() : source));
        return conversation;
    }

    public void addUserMessage(String text) {
        Message message = new Message(Message.Role.USER, text);
        lock.lock();
        try {
            messages.add(message);
        } finally {
            lock.unlock();
        }
        notifyAppend(message);
    }

    public void addAssistantMessage(String text) {
        Message message = new Message(Message.Role.ASSISTANT, text);
        lock.lock();
        try {
            messages.add(message);
        } finally {
            lock.unlock();
        }
        notifyAppend(message);
    }

    public void addAssistantToolCallMessage(String text, List<ToolCall> toolCalls) {
        Message message = new Message(Message.Role.ASSISTANT, text, toolCalls, List.of());
        lock.lock();
        try {
            messages.add(message);
        } finally {
            lock.unlock();
        }
        notifyAppend(message);
    }

    public void addToolResults(List<ToolResult> toolResults) {
        Message message = new Message(Message.Role.TOOL, "", List.of(), toolResults);
        lock.lock();
        try {
            messages.add(message);
        } finally {
            lock.unlock();
        }
        notifyAppend(message);
    }

    public List<Message> getMessages() {
        lock.lock();
        try {
            return copyMessages(messages);
        } finally {
            lock.unlock();
        }
    }

    public void replaceMessages(List<Message> newMessages) {
        List<Message> copied = copyMessages(newMessages == null ? List.of() : newMessages);
        lock.lock();
        try {
            messages.clear();
            messages.addAll(copied);
        } finally {
            lock.unlock();
        }
        notifyReplace(copied);
    }

    public Optional<Message.Role> lastRole() {
        lock.lock();
        try {
            if (messages.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(messages.getLast().role());
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return messages.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return messages.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    private void notifyAppend(Message message) {
        if (onAppend != null) {
            onAppend.accept(new Message(message.role(), message.content(), message.toolCalls(), message.toolResults()));
        }
    }

    private void notifyReplace(List<Message> newMessages) {
        if (onReplace != null) {
            onReplace.accept(copyMessages(newMessages));
        }
    }

    private static List<Message> copyMessages(List<Message> source) {
        List<Message> copied = new ArrayList<>(source.size());
        for (Message message : source) {
            copied.add(new Message(
                    message.role(),
                    message.content(),
                    new ArrayList<>(message.toolCalls()),
                    new ArrayList<>(message.toolResults())));
        }
        return List.copyOf(copied);
    }
}
