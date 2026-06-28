package com.bluecode.compact;

import com.bluecode.conversation.Message;
import com.bluecode.llm.ToolCall;
import com.bluecode.llm.ToolResult;

import java.util.List;

public final class SummaryPrompt {
    private static final String SUMMARY_INSTRUCTION = """
            You are summarizing a coding agent conversation. Output in two phases.

            <analysis>
            Write private analysis here. This section will be discarded.
            </analysis>

            <summary>
            ## 1 主要请求和意图
            ## 2 关键技术概念
            ## 3 文件和代码段
            ## 4 错误和修复
            ## 5 问题解决过程
            ## 6 所有用户消息原文
            ## 7 待办任务
            ## 8 当前工作(最详细)
            ## 9 可能的下一步
            </summary>

            不要调用任何工具,输出纯文本。正式摘要必须保留 9 个小节标题,第 6 节按时间顺序逐条保留用户原文。
            """;

    private SummaryPrompt() {
    }

    public static List<Message> buildSummaryPrompt(List<Message> messages) {
        return List.of(new Message(
                Message.Role.USER,
                SUMMARY_INSTRUCTION + "\n\n[conversation]\n" + serializeConversation(messages)));
    }

    static String serializeConversation(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        for (Message message : messages == null ? List.<Message>of() : messages) {
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(message.role().name().toLowerCase()).append(": ").append(message.content());
            for (ToolCall call : message.toolCalls()) {
                builder.append('\n')
                        .append("[call ")
                        .append(call.name())
                        .append(" id=")
                        .append(call.id())
                        .append(" args=")
                        .append(call.arguments())
                        .append("]");
            }
            for (ToolResult result : message.toolResults()) {
                builder.append('\n')
                        .append("[result id=")
                        .append(result.toolCallId())
                        .append(" isError=")
                        .append(result.isError())
                        .append("] ")
                        .append(result.content());
            }
        }
        return builder.toString();
    }

    public static String extractSummary(String raw) {
        String text = raw == null ? "" : raw;
        int end = text.lastIndexOf("</summary>");
        int start = end >= 0 ? text.lastIndexOf("<summary>", end) : -1;
        if (start < 0 || end < 0 || start >= end) {
            return text.strip();
        }
        return text.substring(start + "<summary>".length(), end).strip();
    }
}
