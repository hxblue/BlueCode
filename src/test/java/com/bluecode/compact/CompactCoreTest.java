package com.bluecode.compact;

import com.bluecode.compact.state.AutoCompactTrackingState;
import com.bluecode.compact.state.ContentReplacementState;
import com.bluecode.compact.state.SessionContext;
import com.bluecode.conversation.ConversationManager;
import com.bluecode.conversation.Message;
import com.bluecode.llm.LlmClient;
import com.bluecode.llm.Request;
import com.bluecode.llm.StreamEvent;
import com.bluecode.llm.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactCoreTest {
    @TempDir
    Path tempDir;

    @Test
    void estimateTokensUsesAnchorAndTailOnly() {
        List<Message> messages = List.of(
                new Message(Message.Role.USER, "aaaa"),
                new Message(Message.Role.ASSISTANT, "bbbbbbbb"));

        long estimated = Token.estimateTokens(1000, messages, 1);

        assertEquals(1003, estimated);
    }

    @Test
    void offloadsSingleLargeToolResultAndFreezesPreview() throws Exception {
        SessionContext session = SessionContext.create(tempDir);
        ContentReplacementState state = new ContentReplacementState();
        String large = "x".repeat(60000);
        List<Message> messages = List.of(new Message(
                Message.Role.TOOL,
                "",
                List.of(),
                List.of(new ToolResult("call-1", large, false))));

        List<Message> first = ContextCompactor.offloadAndSnip(messages, state, session);
        List<Message> second = ContextCompactor.offloadAndSnip(messages, state, session);

        String preview = first.getFirst().toolResults().getFirst().content();
        assertTrue(preview.contains("[content offloaded] original size: 60000 bytes"));
        assertTrue(preview.contains("[saved to]"));
        assertTrue(preview.contains("不要凭头部预览猜测全文"));
        assertEquals(60000, Files.size(session.spillDir().resolve("call-1")));
        assertEquals(preview, second.getFirst().toolResults().getFirst().content());
    }

    @Test
    void offloadsAggregateUntilUnderBudget() throws Exception {
        SessionContext session = SessionContext.create(tempDir);
        ContentReplacementState state = new ContentReplacementState();
        List<ToolResult> results = List.of(
                new ToolResult("a", "a".repeat(80000), false),
                new ToolResult("b", "b".repeat(80000), false),
                new ToolResult("c", "c".repeat(80000), false));
        List<Message> compacted = ContextCompactor.offloadAndSnip(
                List.of(new Message(Message.Role.TOOL, "", List.of(), results)),
                state,
                session);

        long remaining = compacted.getFirst().toolResults().stream()
                .filter(result -> !result.content().contains("[content offloaded]"))
                .mapToLong(result -> result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .sum();

        assertTrue(remaining <= CompactConstants.MESSAGE_AGGREGATE_LIMIT);
        assertTrue(Files.list(session.spillDir()).count() >= 1);
    }

    @Test
    void summaryPromptAndRecoveryHaveStableShape() {
        Message user = new Message(Message.Role.USER, "请读文件");
        List<Message> prompt = SummaryPrompt.buildSummaryPrompt(List.of(user));
        String extracted = SummaryPrompt.extractSummary("xx<summary>正式摘要</summary>yy");
        Recovery.FileReadRecord record = new Recovery.FileReadRecord(
                tempDir.resolve("a.txt").toString(),
                "content",
                java.time.Instant.parse("2026-01-01T00:00:00Z"));

        String recovery = Recovery.buildRecoveryAttachment(
                List.of(record),
                List.of(Map.of("name", "ReadFile", "description", "读取文件", "input_schema", Map.of())));

        assertEquals(1, prompt.size());
        assertTrue(prompt.getFirst().content().contains("## 9 可能的下一步"));
        assertEquals("正式摘要", extracted);
        assertTrue(recovery.contains("## 最近读过的文件"));
        assertTrue(recovery.contains("## 当前可用工具"));
        assertTrue(recovery.contains("## 边界提示"));
        assertTrue(recovery.contains("ReadFile"));
    }

    @Test
    void manualManageAlwaysSummarizesAndDoesNotPassTools() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("第一条");
        FakeClient client = new FakeClient(List.of(new StreamEvent.TextDelta("""
                <analysis>草稿</analysis>
                <summary>## 1 主要请求和意图
                保留摘要</summary>
                """), new StreamEvent.StreamEnd("stop", 10, 5)));
        ContextCompactor.Input input = input(
                conversation,
                client,
                ContextCompactor.TriggerKind.MANUAL,
                1000,
                List.of(Map.of("name", "ReadFile", "description", "读取文件", "input_schema", Map.of())));

        ContextCompactor.Output output = ContextCompactor.manage(input);

        assertEquals(1, client.requests.size());
        assertTrue(client.requests.getFirst().tools().isEmpty());
        assertTrue(conversation.getMessages().getFirst().content().contains("历史会话摘要"));
        assertTrue(conversation.getMessages().getFirst().content().contains("保留摘要"));
        assertTrue(output.afterTokens() > 0);
    }

    @Test
    void autoManageSkipsSummaryBelowThreshold() throws Exception {
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("短消息");
        FakeClient client = new FakeClient(List.of(new StreamEvent.TextDelta("unused"), new StreamEvent.StreamEnd("stop", 1, 1)));
        ContextCompactor.Input input = input(conversation, client, ContextCompactor.TriggerKind.AUTO, 10, List.of());

        ContextCompactor.manage(input);

        assertTrue(client.requests.isEmpty());
        assertFalse(conversation.getMessages().isEmpty());
    }

    private ContextCompactor.Input input(
            ConversationManager conversation,
            FakeClient client,
            ContextCompactor.TriggerKind trigger,
            long estimated,
            List<Map<String, Object>> tools) throws Exception {
        return new ContextCompactor.Input(
                conversation,
                client,
                "fake",
                200000,
                tools,
                new ContentReplacementState(),
                new Recovery.RecoveryState(),
                new AutoCompactTrackingState(),
                SessionContext.create(tempDir),
                0,
                0,
                estimated,
                trigger);
    }

    private static final class FakeClient implements LlmClient {
        private final List<StreamEvent> script;
        private final List<Request> requests = new ArrayList<>();

        private FakeClient(List<StreamEvent> script) {
            this.script = script;
        }

        @Override
        public BlockingQueue<StreamEvent> stream(Request request) {
            requests.add(request);
            LinkedBlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
            queue.addAll(script);
            return queue;
        }

        @Override
        public String model() {
            return "fake-model";
        }
    }
}
