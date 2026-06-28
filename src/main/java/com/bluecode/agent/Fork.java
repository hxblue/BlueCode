package com.bluecode.agent;

import com.bluecode.conversation.Message;
import com.bluecode.llm.ToolCall;
import com.bluecode.llm.ToolResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Fork {
    public static final String FORK_BOILERPLATE_TAG = "<fork_boilerplate>";
    public static final String FORK_BOILERPLATE = """
            <fork_boilerplate>
            你是一个 Fork 出来的工作进程,不是主 Agent。
            规则:
            1. 不能再次 Fork 或启动 Agent。
            2. 不要闲聊、不要提问、不要请求确认。
            3. 直接使用工具完成分配给你的任务。
            4. 严格限制在任务范围内。
            5. 最终报告以 "Scope:" 开头,控制在 500 字以内。
            </fork_boilerplate>

            """;

    private Fork() {
    }

    public static List<Message> buildForkedMessages(List<Message> parentMessages, String task) {
        List<Message> copied = new ArrayList<>();
        for (Message message : parentMessages == null ? List.<Message>of() : parentMessages) {
            copied.add(new Message(
                    message.role(),
                    message.content(),
                    new ArrayList<>(message.toolCalls()),
                    new ArrayList<>(message.toolResults())));
        }
        fillDanglingToolResults(copied);
        copied.add(new Message(Message.Role.USER, FORK_BOILERPLATE + (task == null ? "" : task)));
        return List.copyOf(copied);
    }

    public static boolean isForkContext(List<Message> messages) {
        if (messages == null) {
            return false;
        }
        return messages.stream().anyMatch(message -> message.content().contains(FORK_BOILERPLATE_TAG));
    }

    private static void fillDanglingToolResults(List<Message> messages) {
        Set<String> completed = new HashSet<>();
        for (Message message : messages) {
            for (ToolResult result : message.toolResults()) {
                completed.add(result.toolCallId());
            }
        }
        List<ToolResult> placeholders = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.role() != Message.Role.ASSISTANT || message.toolCalls().isEmpty()) {
                continue;
            }
            for (ToolCall call : message.toolCalls()) {
                if (!completed.contains(call.id())) {
                    placeholders.add(new ToolResult(call.id(), "[forked, skipped dangling tool result]", true));
                }
            }
            break;
        }
        if (!placeholders.isEmpty()) {
            messages.add(new Message(Message.Role.TOOL, "", List.of(), placeholders));
        }
    }
}
