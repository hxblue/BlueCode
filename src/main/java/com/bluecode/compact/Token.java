package com.bluecode.compact;

import com.bluecode.conversation.Message;
import com.bluecode.llm.ToolCall;
import com.bluecode.llm.ToolResult;
import com.bluecode.llm.Usage;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Token {
    private Token() {
    }

    public static long usageAnchor(Usage usage) {
        if (usage == null) {
            return 0;
        }
        return usage.inputTokens() + usage.outputTokens() + usage.cacheRead() + usage.cacheWrite();
    }

    public static long estimateTokens(long anchor, List<Message> allMessages, int anchorMsgLen) {
        List<Message> messages = allMessages == null ? List.of() : allMessages;
        int safeStart = Math.min(Math.max(anchorMsgLen, 0), messages.size());
        long chars = messageChars(messages.subList(safeStart, messages.size()));
        return anchor + (long) Math.ceil(chars / CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
    }

    static long messageChars(List<Message> messages) {
        long total = 0;
        for (Message message : messages == null ? List.<Message>of() : messages) {
            total += utf8Length(message.content());
            for (ToolCall call : message.toolCalls()) {
                total += utf8Length(call.arguments());
            }
            for (ToolResult result : message.toolResults()) {
                total += utf8Length(result.content());
            }
        }
        return total;
    }

    static long messageTokens(Message message) {
        return (long) Math.ceil(messageChars(List.of(message)) / CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
    }

    static int utf8Length(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
