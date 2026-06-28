package com.bluecode.compact;

import com.bluecode.compact.Recovery.FileReadRecord;
import com.bluecode.compact.state.AutoCompactTrackingState;
import com.bluecode.compact.state.ContentReplacementState;
import com.bluecode.compact.state.ContentReplacementState.Decision;
import com.bluecode.compact.state.ContentReplacementState.DecisionResult;
import com.bluecode.compact.state.SessionContext;
import com.bluecode.conversation.ConversationManager;
import com.bluecode.conversation.Message;
import com.bluecode.llm.LlmClient;
import com.bluecode.llm.PromptTooLongException;
import com.bluecode.llm.Request;
import com.bluecode.llm.StreamEvent;
import com.bluecode.llm.SystemPrompt;
import com.bluecode.llm.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class ContextCompactor {
    private static final Logger LOGGER = Logger.getLogger(ContextCompactor.class.getName());
    private static final String SUMMARY_BRIDGE = "(已加载上下文摘要与恢复信息。请继续。)";

    private ContextCompactor() {
    }

    public enum TriggerKind {
        AUTO,
        MANUAL,
        EMERGENCY
    }

    public record Input(
            ConversationManager conversation,
            LlmClient client,
            String model,
            int contextWindow,
            List<Map<String, Object>> toolDefs,
            ContentReplacementState replacement,
            Recovery.RecoveryState recovery,
            AutoCompactTrackingState autoTracking,
            SessionContext session,
            long usageAnchor,
            int anchorMsgLen,
            long estimatedToken,
            TriggerKind trigger) {
        public Input {
            if (conversation == null) {
                throw new IllegalArgumentException("conversation 不能为空");
            }
            if (client == null) {
                throw new IllegalArgumentException("client 不能为空");
            }
            toolDefs = toolDefs == null ? List.of() : List.copyOf(toolDefs);
            if (replacement == null) {
                throw new IllegalArgumentException("replacement 不能为空");
            }
            if (recovery == null) {
                throw new IllegalArgumentException("recovery 不能为空");
            }
            if (autoTracking == null) {
                throw new IllegalArgumentException("autoTracking 不能为空");
            }
            if (session == null) {
                throw new IllegalArgumentException("session 不能为空");
            }
            trigger = trigger == null ? TriggerKind.AUTO : trigger;
        }
    }

    public record Output(long beforeTokens, long afterTokens) {
    }

    public record CompactResult(List<Message> newMessages, long beforeTokens, long afterTokens) {
    }

    public static Output manage(Input input) throws CompactException {
        if (input.trigger() == TriggerKind.MANUAL) {
            CompactResult result = forceCompact(input);
            input.conversation().replaceMessages(result.newMessages());
            return new Output(input.estimatedToken(), result.afterTokens());
        }
        if (input.trigger() == TriggerKind.EMERGENCY) {
            List<Message> layer1 = offloadAndSnip(
                    input.conversation().getMessages(),
                    input.replacement(),
                    input.session());
            input.conversation().replaceMessages(layer1);
            CompactResult result = forceCompact(input);
            input.conversation().replaceMessages(result.newMessages());
            return new Output(input.estimatedToken(), result.afterTokens());
        }

        List<Message> layer1 = offloadAndSnip(input.conversation().getMessages(), input.replacement(), input.session());
        input.conversation().replaceMessages(layer1);
        long estimated = Token.estimateTokens(input.usageAnchor(), layer1, input.anchorMsgLen());
        if (input.contextWindow() <= CompactConstants.SUMMARY_RESERVE + CompactConstants.AUTO_SAFETY_MARGIN) {
            LOGGER.warning("context_window 过小，跳过自动摘要: " + input.contextWindow());
            return new Output(input.estimatedToken(), estimated);
        }
        long threshold = input.contextWindow() - CompactConstants.SUMMARY_RESERVE - CompactConstants.AUTO_SAFETY_MARGIN;
        if (estimated < threshold || input.autoTracking().tripped()) {
            return new Output(input.estimatedToken(), estimated);
        }
        CompactResult result = autoCompact(input);
        input.conversation().replaceMessages(result.newMessages());
        return new Output(input.estimatedToken(), result.afterTokens());
    }

    public static List<Message> offloadAndSnip(
            List<Message> messages,
            ContentReplacementState state,
            SessionContext session) {
        List<Message> out = new ArrayList<>();
        for (Message message : messages == null ? List.<Message>of() : messages) {
            if (message.role() != Message.Role.TOOL || message.toolResults().isEmpty()) {
                out.add(copyMessage(message));
                continue;
            }
            out.add(rewriteToolMessage(message, state, session));
        }
        return List.copyOf(out);
    }

    static void spillSingle(SessionContext session, String toolUseId, String content) throws IOException {
        Files.createDirectories(session.spillDir());
        Path path = session.spillDir().resolve(toolUseId);
        if (Files.exists(path)) {
            return;
        }
        Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    static String buildPreview(int originalBytes, String head, Path spillPath) {
        StringBuilder builder = new StringBuilder();
        builder.append("[content offloaded] original size: ").append(originalBytes).append(" bytes\n");
        builder.append("[saved to] ").append(spillPath).append('\n');
        builder.append("[head preview]\n");
        if (head != null && !head.isEmpty()) {
            builder.append(head).append('\n');
        }
        builder.append("完整内容已保存到上述路径,如需查看请用文件读取工具读取该路径,不要凭头部预览猜测全文");
        return builder.toString();
    }

    static String headPreview(String content) {
        String text = content == null ? "" : content;
        String[] lines = text.split("\\R", CompactConstants.PREVIEW_HEAD_LINES + 1);
        StringBuilder byLine = new StringBuilder();
        int lineCount = Math.min(CompactConstants.PREVIEW_HEAD_LINES, lines.length);
        for (int i = 0; i < lineCount; i++) {
            if (i > 0) {
                byLine.append('\n');
            }
            byLine.append(lines[i]);
        }
        return truncateUtf8(byLine.toString(), CompactConstants.PREVIEW_HEAD_BYTES);
    }

    public static CompactResult autoCompact(Input input) throws CompactException {
        try {
            List<Message> newMessages = runSummary(input);
            input.autoTracking().recordSuccess();
            long after = Token.estimateTokens(0, newMessages, 0);
            return new CompactResult(newMessages, input.estimatedToken(), after);
        } catch (CompactException e) {
            input.autoTracking().recordFailure();
            throw e;
        }
    }

    public static CompactResult forceCompact(Input input) throws CompactException {
        List<Message> newMessages = runSummary(input);
        long after = Token.estimateTokens(0, newMessages, 0);
        return new CompactResult(newMessages, input.estimatedToken(), after);
    }

    static List<Message> runSummary(Input input) throws CompactException {
        List<Message> oldMessages = input.conversation().getMessages();
        List<FileReadRecord> recoverySnapshot = input.recovery().snapshot();
        String summaryText;
        try {
            summaryText = summarizeOnce(input, oldMessages);
        } catch (PromptTooLongException e) {
            summaryText = ptlRetry(input, oldMessages, e);
        } catch (IOException e) {
            throw new CompactException("摘要请求失败: " + e.getMessage(), e);
        }
        String recoveryText = Recovery.buildRecoveryAttachment(recoverySnapshot, input.toolDefs());
        Message summaryAndRecovery = new Message(
                Message.Role.USER,
                "## 历史会话摘要\n" + summaryText + "\n\n" + recoveryText);
        return joinAfterSummary(summaryAndRecovery, pickRecentTail(oldMessages));
    }

    static String summarizeOnce(Input input, List<Message> messages) throws PromptTooLongException, IOException {
        Request request = new Request(SummaryPrompt.buildSummaryPrompt(messages), List.of(), new SystemPrompt("", ""), "");
        BlockingQueue<StreamEvent> stream = input.client().stream(request);
        StringBuilder text = new StringBuilder();
        try {
            while (true) {
                StreamEvent event = stream.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                switch (event) {
                    case StreamEvent.TextDelta delta -> text.append(delta.text());
                    case StreamEvent.ThinkingDelta ignored -> {
                    }
                    case StreamEvent.ToolCallStart ignored -> {
                    }
                    case StreamEvent.ToolCallDelta ignored -> {
                    }
                    case StreamEvent.ToolCallComplete ignored -> {
                    }
                    case StreamEvent.UsageEvent ignored -> {
                    }
                    case StreamEvent.StreamEnd ignored -> {
                        return SummaryPrompt.extractSummary(text.toString());
                    }
                    case StreamEvent.Error error -> {
                        if (error.cause() instanceof PromptTooLongException ptl) {
                            throw ptl;
                        }
                        if (looksPromptTooLong(error.message())) {
                            throw new PromptTooLongException(new IOException(error.message()));
                        }
                        throw new IOException(error.message(), error.cause());
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("摘要请求被中断", e);
        }
    }

    static String ptlRetry(Input input, List<Message> messages, Throwable firstError) throws CompactException {
        List<List<Message>> groups = new ArrayList<>(groupByUserTurn(messages));
        Throwable lastError = firstError;
        int retry = 0;
        while (!groups.isEmpty()) {
            retry++;
            int drop = retry <= CompactConstants.PTL_RETRY_LIMIT
                    ? 1
                    : Math.max(1, (int) Math.ceil(groups.size() * CompactConstants.PTL_DROP_PERCENTAGE));
            for (int i = 0; i < drop && !groups.isEmpty(); i++) {
                groups.removeFirst();
            }
            if (groups.isEmpty()) {
                break;
            }
            try {
                return summarizeOnce(input, flatten(groups));
            } catch (PromptTooLongException e) {
                lastError = e;
            } catch (IOException e) {
                throw new CompactException("摘要 PTL 重试失败: " + e.getMessage(), e);
            }
        }
        throw new CompactException("摘要 PTL 重试已用尽", lastError);
    }

    static List<Message> pickRecentTail(List<Message> messages) {
        List<Message> safeMessages = messages == null ? List.of() : messages;
        if (safeMessages.isEmpty()) {
            return List.of();
        }
        int start = safeMessages.size();
        long tokens = 0;
        int count = 0;
        for (int i = safeMessages.size() - 1; i >= 0; i--) {
            tokens += Token.messageTokens(safeMessages.get(i));
            count++;
            start = i;
            if (tokens >= CompactConstants.RECENT_KEEP_TOKENS
                    && count >= CompactConstants.RECENT_KEEP_MESSAGES) {
                break;
            }
        }
        if (safeMessages.get(start).role() == Message.Role.TOOL) {
            for (int i = start - 1; i >= 0; i--) {
                if (safeMessages.get(i).role() == Message.Role.ASSISTANT
                        && !safeMessages.get(i).toolCalls().isEmpty()) {
                    start = i;
                    break;
                }
            }
        }
        return List.copyOf(safeMessages.subList(start, safeMessages.size()));
    }

    static List<Message> joinAfterSummary(Message summaryAndRecovery, List<Message> recent) {
        List<Message> out = new ArrayList<>();
        out.add(copyMessage(summaryAndRecovery));
        List<Message> tail = new ArrayList<>(recent == null ? List.of() : recent);
        while (!tail.isEmpty() && tail.getFirst().role() == Message.Role.TOOL) {
            tail.removeFirst();
        }
        if (!tail.isEmpty() && tail.getFirst().role() == Message.Role.USER) {
            out.add(new Message(Message.Role.ASSISTANT, SUMMARY_BRIDGE));
        }
        for (Message message : tail) {
            out.add(copyMessage(message));
        }
        return List.copyOf(out);
    }

    static List<List<Message>> groupByUserTurn(List<Message> messages) {
        List<List<Message>> groups = new ArrayList<>();
        List<Message> current = new ArrayList<>();
        for (Message message : messages == null ? List.<Message>of() : messages) {
            if (message.role() == Message.Role.USER && !current.isEmpty()) {
                groups.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            current.add(copyMessage(message));
        }
        if (!current.isEmpty()) {
            groups.add(List.copyOf(current));
        }
        return List.copyOf(groups);
    }

    private static Message rewriteToolMessage(
            Message message,
            ContentReplacementState state,
            SessionContext session) {
        List<ToolResult> originalResults = message.toolResults();
        List<ToolResult> rewritten = new ArrayList<>(originalResults);
        List<Candidate> candidates = new ArrayList<>();
        long remainingBytes = 0;
        for (int i = 0; i < originalResults.size(); i++) {
            ToolResult result = originalResults.get(i);
            int bytes = Token.utf8Length(result.content());
            remainingBytes += bytes;
            candidates.add(new Candidate(i, result, bytes));
        }
        candidates.sort(Comparator.comparingInt(Candidate::bytes).reversed());

        for (Candidate candidate : candidates) {
            boolean shouldReplace = candidate.bytes() > CompactConstants.SINGLE_RESULT_LIMIT
                    || remainingBytes > CompactConstants.MESSAGE_AGGREGATE_LIMIT;
            String content = candidate.result().content();
            String newContent;
            if (shouldReplace) {
                newContent = state.decideOnce(candidate.result().toolCallId(), content, () -> {
                    try {
                        spillSingle(session, candidate.result().toolCallId(), content);
                    } catch (IOException e) {
                        return new DecisionResult(Decision.SKIP, null);
                    }
                    Path spillPath = session.spillDir().resolve(candidate.result().toolCallId());
                    return new DecisionResult(
                            Decision.REPLACED,
                            buildPreview(candidate.bytes(), headPreview(content), spillPath));
                });
            } else {
                newContent = state.decideOnce(
                        candidate.result().toolCallId(),
                        content,
                        () -> new DecisionResult(Decision.KEPT, null));
            }
            rewritten.set(candidate.index(), new ToolResult(
                    candidate.result().toolCallId(),
                    newContent,
                    candidate.result().isError()));
            if (shouldReplace && !newContent.equals(content)) {
                remainingBytes -= candidate.bytes();
            }
        }
        return new Message(message.role(), message.content(), message.toolCalls(), rewritten);
    }

    private static List<Message> flatten(List<List<Message>> groups) {
        List<Message> out = new ArrayList<>();
        for (List<Message> group : groups) {
            out.addAll(group);
        }
        return List.copyOf(out);
    }

    private static Message copyMessage(Message message) {
        return new Message(message.role(), message.content(), message.toolCalls(), message.toolResults());
    }

    private static String truncateUtf8(String text, int maxBytes) {
        StringBuilder builder = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            String value = new String(Character.toChars(codePoint));
            int bytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (used + bytes > maxBytes) {
                break;
            }
            builder.append(value);
            used += bytes;
            i += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private static boolean looksPromptTooLong(String message) {
        String lower = message == null ? "" : message.toLowerCase();
        return lower.contains("prompt is too long")
                || lower.contains("context_length")
                || lower.contains("context length")
                || lower.contains("context window")
                || lower.contains("maximum context")
                || lower.contains("too many tokens");
    }

    private record Candidate(int index, ToolResult result, int bytes) {
    }
}
