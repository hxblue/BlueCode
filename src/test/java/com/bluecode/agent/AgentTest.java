package com.bluecode.agent;

import com.bluecode.conversation.ConversationManager;
import com.bluecode.conversation.Message;
import com.bluecode.hook.Action;
import com.bluecode.hook.AtomCondition;
import com.bluecode.hook.CombineMode;
import com.bluecode.hook.Condition;
import com.bluecode.hook.HookEngine;
import com.bluecode.hook.HookRule;
import com.bluecode.llm.LlmClient;
import com.bluecode.llm.Request;
import com.bluecode.llm.StreamEvent;
import com.bluecode.permission.ExactMatcher;
import com.bluecode.permission.Mode;
import com.bluecode.permission.Outcome;
import com.bluecode.permission.PermissionEngine;
import com.bluecode.prompt.Reminder;
import com.bluecode.tool.Registry;
import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {
    @TempDir
    Path tempDir;

    @Test
    void runsMultipleRoundsUntilFinalText() throws Exception {
        Path file = tempDir.resolve("note.txt");
        Files.writeString(file, "BlueCode can read files.");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("call-1", "ReadFile", "{\"path\":\"" + escape(file) + "\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 11, 3)),
                List.of(new StreamEvent.TextDelta("文件内容说明: BlueCode can read files."),
                        new StreamEvent.StreamEnd("stop", 20, 8))
        ));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("读文件并总结");

        List<Event> events = drain(agent(client, Registry.createDefault()).run(conversation, Mode.BYPASS, new CancelToken()));

        assertTrue(events.stream().anyMatch(event -> event instanceof Event.Iter iter && iter.value() == 1));
        assertTrue(events.stream().anyMatch(event -> event instanceof Event.Iter iter && iter.value() == 2));
        assertTrue(events.stream().anyMatch(event -> event instanceof Event.Tool tool && tool.event().phase() == Phase.START));
        assertTrue(events.stream().anyMatch(event -> event instanceof Event.Tool tool && tool.event().phase() == Phase.END && !tool.event().isError()));
        assertTrue(events.stream().anyMatch(event -> event instanceof Event.UsageReport usage && usage.usage().input() == 11));
        assertTrue(events.stream().anyMatch(event -> event instanceof Event.Text text && text.delta().contains("BlueCode")));
        assertInstanceOf(Event.Done.class, events.getLast());

        List<Message> messages = conversation.getMessages();
        assertEquals(Message.Role.ASSISTANT, messages.get(1).role());
        assertEquals(1, messages.get(1).toolCalls().size());
        assertEquals(Message.Role.TOOL, messages.get(2).role());
        assertEquals(Message.Role.ASSISTANT, messages.get(3).role());
        assertTrue(messages.get(3).content().contains("BlueCode"));
        assertEquals(2, client.requestCount);
    }

    @Test
    void stopsAtIterationLimit() throws Exception {
        FakeClient client = FakeClient.repeating(List.of(
                new StreamEvent.ToolCallComplete("call-loop", "ReadFile", "{\"path\":\"missing.txt\"}"),
                new StreamEvent.StreamEnd("tool_calls", 1, 1)
        ));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("一直调用工具");

        List<Event> events = drain(agent(client, Registry.createDefault()).run(conversation, Mode.BYPASS, new CancelToken()));

        assertEquals(Agent.MAX_ITERATIONS, client.requestCount);
        assertTrue(events.stream().anyMatch(event -> event instanceof Event.Notice notice
                && notice.message().equals(Agent.NOTICE_MAX_ITER)));
        assertEquals(Message.Role.ASSISTANT, conversation.lastRole().orElseThrow());
    }

    @Test
    void stopsAfterConsecutiveUnknownToolsAndResetsOnKnownTool() throws Exception {
        FakeClient unknownOnly = FakeClient.repeating(List.of(
                new StreamEvent.ToolCallComplete("bad", "MissingTool", "{}"),
                new StreamEvent.StreamEnd("tool_calls", 1, 1)
        ));
        ConversationManager first = new ConversationManager();
        first.addUserMessage("调用不存在工具");

        List<Event> firstEvents = drain(agent(unknownOnly, Registry.createDefault()).run(first, Mode.BYPASS, new CancelToken()));

        assertEquals(Agent.MAX_UNKNOWN_RUN, unknownOnly.requestCount);
        assertTrue(firstEvents.stream().anyMatch(event -> event instanceof Event.Notice notice
                && notice.message().equals(Agent.NOTICE_UNKNOWN_TOOLS)));

        Path file = tempDir.resolve("known.txt");
        Files.writeString(file, "known");
        FakeClient reset = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("bad-1", "MissingTool", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 1, 1)),
                List.of(new StreamEvent.ToolCallComplete("known", "ReadFile", "{\"path\":\"" + escape(file) + "\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 1, 1)),
                List.of(new StreamEvent.ToolCallComplete("bad-2", "MissingTool", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 1, 1)),
                List.of(new StreamEvent.ToolCallComplete("bad-3", "MissingTool", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 1, 1)),
                List.of(new StreamEvent.ToolCallComplete("bad-4", "MissingTool", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 1, 1))
        ));
        ConversationManager second = new ConversationManager();
        second.addUserMessage("中间混入已知工具");

        drain(agent(reset, Registry.createDefault()).run(second, Mode.BYPASS, new CancelToken()));

        assertEquals(5, reset.requestCount);
    }

    @Test
    void executesConsecutiveReadOnlyToolsConcurrentlyThenSideEffectToolSerially() throws Exception {
        AtomicInteger runningReadOnly = new AtomicInteger();
        AtomicInteger readOnlyPeak = new AtomicInteger();
        ConcurrentLinkedQueue<Long> readOnlyEnds = new ConcurrentLinkedQueue<>();
        AtomicLong sideEffectStart = new AtomicLong();
        Registry registry = new Registry();
        registry.register(new ProbeTool("RO", true, args -> {
            int running = runningReadOnly.incrementAndGet();
            readOnlyPeak.accumulateAndGet(running, Math::max);
            Thread.sleep(180);
            runningReadOnly.decrementAndGet();
            readOnlyEnds.add(System.nanoTime());
            return Result.ok("ro-" + args.get("id"));
        }));
        registry.register(new ProbeTool("RW", false, args -> {
            sideEffectStart.set(System.nanoTime());
            return Result.ok("rw");
        }));
        FakeClient client = new FakeClient(List.of(
                List.of(
                        new StreamEvent.ToolCallComplete("ro-1", "RO", "{\"id\":\"1\"}"),
                        new StreamEvent.ToolCallComplete("ro-2", "RO", "{\"id\":\"2\"}"),
                        new StreamEvent.ToolCallComplete("rw", "RW", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 1, 1)),
                List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("stop", 1, 1))
        ));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("分批执行");

        drain(agent(client, registry).run(conversation, Mode.BYPASS, new CancelToken()));

        assertTrue(readOnlyPeak.get() >= 2, "只读工具应并发执行");
        long lastReadOnlyEnd = readOnlyEnds.stream().mapToLong(Long::longValue).max().orElseThrow();
        assertTrue(sideEffectStart.get() > lastReadOnlyEnd, "有副作用工具应在只读批完成后开始");
        List<com.bluecode.llm.ToolResult> results = conversation.getMessages().stream()
                .filter(message -> message.role() == Message.Role.TOOL)
                .findFirst()
                .orElseThrow()
                .toolResults();
        assertEquals(List.of("ro-1", "ro-2", "rw"), results.stream().map(com.bluecode.llm.ToolResult::content).toList());
    }

    @Test
    void cancellationKeepsHistoryLegal() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Registry registry = new Registry();
        registry.register(new ProbeTool("Slow", false, args -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            return Result.ok("late-result");
        }));
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("slow", "Slow", "{}"),
                        new StreamEvent.StreamEnd("tool_calls", 1, 1))
        ));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("取消当前轮");
        CancelToken cancel = new CancelToken();
        BlockingQueue<Event> queue = agent(client, registry).run(conversation, Mode.BYPASS, cancel);

        List<Event> events = new ArrayList<>();
        while (true) {
            Event event = queue.poll(5, TimeUnit.SECONDS);
            if (event == null) {
                throw new AssertionError("等待 Slow 工具开始超时");
            }
            events.add(event);
            if (event instanceof Event.Tool tool && tool.event().phase() == Phase.START) {
                break;
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        cancel.cancel();
        release.countDown();
        events.addAll(drain(queue));

        assertTrue(events.stream().anyMatch(event -> event instanceof Event.Notice notice
                && notice.message().equals(Agent.NOTICE_CANCELLED)));
        List<Message> messages = conversation.getMessages();
        assertEquals(Message.Role.TOOL, messages.get(2).role());
        assertFalse(messages.get(2).toolResults().isEmpty());
        assertEquals(Message.Role.ASSISTANT, messages.getLast().role());
        assertEquals(Agent.NOTICE_CANCELLED, messages.getLast().content());
    }

    @Test
    void asksBeforeSideEffectToolAndAllowsOnce() throws Exception {
        Path file = tempDir.resolve("approved.txt");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("write", "WriteFile",
                                "{\"path\":\"" + escape(file) + "\",\"content\":\"approved\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 1, 1)),
                List.of(new StreamEvent.TextDelta("done"), new StreamEvent.StreamEnd("stop", 1, 1))
        ));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("写文件");
        BlockingQueue<Event> queue = agent(client, Registry.createDefault()).run(conversation, Mode.DEFAULT, new CancelToken());

        Event.Approval approval = waitForApproval(queue);
        assertTrue(approval.request().reason().contains("需要确认"));
        approval.request().respond().offer(Outcome.ALLOW_ONCE);
        List<Event> events = drain(queue);

        assertEquals("approved", Files.readString(file));
        assertTrue(events.stream().anyMatch(event -> event instanceof Event.Tool tool
                && tool.event().phase() == Phase.END
                && !tool.event().isError()));
        assertEquals(Message.Role.ASSISTANT, conversation.getMessages().getLast().role());
    }

    @Test
    void planModeUsesOnlyReadOnlyToolsAndInjectsReminderByRound() throws Exception {
        Path file = tempDir.resolve("plan.txt");
        Files.writeString(file, "plan context");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("call-plan", "ReadFile", "{\"path\":\"" + escape(file) + "\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 1, 1)),
                List.of(new StreamEvent.TextDelta("计划如下"), new StreamEvent.StreamEnd("stop", 1, 1))
        ));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("先做计划");

        drain(agent(client, Registry.createDefault()).run(conversation, Mode.PLAN, new CancelToken()));

        assertEquals(List.of("ReadFile", "Glob", "Grep"),
                client.requests.getFirst().tools().stream().map(tool -> (String) tool.get("name")).toList());
        assertTrue(client.requests.getFirst().system().stable().contains("BlueCode"));
        assertTrue(client.requests.getFirst().system().environment().contains("工作目录"));
        assertEquals(client.requests.getFirst().system().stable(), client.requests.getLast().system().stable());
        assertTrue(client.requests.getFirst().reminder().contains("<system-reminder>"));
        assertTrue(client.requests.getFirst().reminder().contains("当前处于 PLAN MODE"));
        assertTrue(client.requests.getLast().reminder().contains("PLAN MODE 仍然生效"));
        assertFalse(conversation.getMessages().stream().anyMatch(message -> message.content().contains("<system-reminder>")));
    }

    @Test
    void hookPromptIsIncludedInNextReminder() throws Exception {
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.TextDelta("好的"), new StreamEvent.StreamEnd("stop", 1, 1))
        ));
        HookEngine hookEngine = new HookEngine(List.of(new HookRule(
                "pre-user-reminder",
                com.bluecode.hook.Event.PRE_USER_MESSAGE,
                null,
                new Action.Prompt("hook reminder"),
                false,
                false,
                Duration.ofSeconds(1),
                "test")), List.of(), new com.bluecode.hook.HookExecutor());
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("测试 reminder");

        drain(agent(client, Registry.createDefault(), hookEngine).run(conversation, Mode.BYPASS, new CancelToken()));

        assertTrue(client.requests.getFirst().reminder().contains("hook reminder"));
    }

    @Test
    void preToolUseHookBlocksWriteBeforeToolExecutes() throws Exception {
        Path target = tempDir.resolve("blocked.txt");
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.ToolCallComplete("write", "WriteFile",
                                "{\"path\":\"" + escape(target) + "\",\"content\":\"blocked\"}"),
                        new StreamEvent.StreamEnd("tool_calls", 1, 1)),
                List.of(new StreamEvent.TextDelta("已停止"), new StreamEvent.StreamEnd("stop", 1, 1))
        ));
        HookEngine hookEngine = new HookEngine(List.of(new HookRule(
                "block-write",
                com.bluecode.hook.Event.PRE_TOOL_USE,
                new Condition(CombineMode.ALL_OF, List.of(
                        new AtomCondition("tool_name", new ExactMatcher("write_file")))),
                new Action.Shell("echo blocked >&2; exit 2"),
                false,
                false,
                Duration.ofSeconds(5),
                "test")), List.of(), new com.bluecode.hook.HookExecutor());
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("写文件");

        List<Event> events = drain(agent(client, Registry.createDefault(), hookEngine)
                .run(conversation, Mode.BYPASS, new CancelToken()));

        assertFalse(Files.exists(target));
        assertTrue(events.stream().anyMatch(event -> event instanceof Event.Tool tool
                && tool.event().phase() == Phase.END
                && tool.event().isError()
                && tool.event().result().contains("[hook block-write] blocked")));
        assertTrue(conversation.getMessages().stream()
                .filter(message -> message.role() == Message.Role.TOOL)
                .flatMap(message -> message.toolResults().stream())
                .anyMatch(result -> result.isError() && result.content().contains("[hook block-write] blocked")));
    }

    @Test
    void forwardsCacheUsageFromClient() throws Exception {
        FakeClient client = new FakeClient(List.of(
                List.of(new StreamEvent.UsageEvent(new com.bluecode.llm.Usage(100, 20, 30, 40)),
                        new StreamEvent.TextDelta("done"),
                        new StreamEvent.StreamEnd("stop", 0, 0))
        ));
        ConversationManager conversation = new ConversationManager();
        conversation.addUserMessage("统计用量");

        List<Event> events = drain(agent(client, Registry.createDefault()).run(conversation, Mode.BYPASS, new CancelToken()));

        assertTrue(events.stream().anyMatch(event -> event instanceof Event.UsageReport usage
                && usage.usage().input() == 100
                && usage.usage().output() == 20
                && usage.usage().cacheWrite() == 30
                && usage.usage().cacheRead() == 40));
    }

    @Test
    void doDirectiveLivesInReminderClass() {
        assertEquals("请按上面的计划开始执行。", Reminder.EXECUTE_DIRECTIVE);
    }

    private List<Event> drain(BlockingQueue<Event> queue) throws InterruptedException {
        List<Event> events = new ArrayList<>();
        while (true) {
            Event event = queue.poll(5, TimeUnit.SECONDS);
            if (event == null) {
                throw new AssertionError("等待 Agent 事件超时，已收到: " + events);
            }
            events.add(event);
            if (event instanceof Event.Done || event instanceof Event.Failed) {
                return events;
            }
        }
    }

    private Event.Approval waitForApproval(BlockingQueue<Event> queue) throws InterruptedException {
        while (true) {
            Event event = queue.poll(5, TimeUnit.SECONDS);
            if (event == null) {
                throw new AssertionError("等待审批事件超时");
            }
            if (event instanceof Event.Approval approval) {
                return approval;
            }
            if (event instanceof Event.Done || event instanceof Event.Failed) {
                throw new AssertionError("尚未收到审批事件就结束: " + event);
            }
        }
    }

    private Agent agent(LlmClient client, Registry registry) {
        return new Agent(client, registry, "test", PermissionEngine.create(tempDir));
    }

    private Agent agent(LlmClient client, Registry registry, HookEngine hookEngine) {
        return Agent.builder()
                .client(client)
                .registry(registry)
                .version("test")
                .engine(PermissionEngine.create(tempDir))
                .hookEngine(hookEngine)
                .build();
    }

    private String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    @FunctionalInterface
    private interface ToolBody {
        Result execute(Map<String, Object> args) throws Exception;
    }

    private static final class ProbeTool implements Tool {
        private final String name;
        private final boolean readOnly;
        private final ToolBody body;

        private ProbeTool(String name, boolean readOnly, ToolBody body) {
            this.name = name;
            this.readOnly = readOnly;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public Map<String, Object> schema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        public boolean readOnly() {
            return readOnly;
        }

        @Override
        public Result execute(Map<String, Object> args) {
            try {
                return body.execute(args);
            } catch (Exception e) {
                return Result.error(e.getMessage());
            }
        }
    }

    private static final class FakeClient implements LlmClient {
        private final List<List<StreamEvent>> scripts;
        private final boolean repeating;
        private final List<Request> requests = new ArrayList<>();
        private int requestCount;

        private FakeClient(List<List<StreamEvent>> scripts) {
            this(scripts, false);
        }

        private FakeClient(List<List<StreamEvent>> scripts, boolean repeating) {
            this.scripts = scripts;
            this.repeating = repeating;
        }

        static FakeClient repeating(List<StreamEvent> script) {
            return new FakeClient(List.of(script), true);
        }

        @Override
        public BlockingQueue<StreamEvent> stream(Request request) {
            LinkedBlockingQueue<StreamEvent> queue = new LinkedBlockingQueue<>();
            requests.add(request);
            int index = repeating ? 0 : Math.min(requestCount, scripts.size() - 1);
            requestCount++;
            queue.addAll(scripts.get(index));
            return queue;
        }

        @Override
        public String model() {
            return "fake-model";
        }
    }
}
