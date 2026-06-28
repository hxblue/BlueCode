package com.bluecode.agent;

import com.bluecode.compact.CompactConstants;
import com.bluecode.compact.CompactException;
import com.bluecode.compact.ContextCompactor;
import com.bluecode.compact.Token;
import com.bluecode.conversation.ConversationManager;
import com.bluecode.conversation.Message;
import com.bluecode.hook.DispatchResult;
import com.bluecode.hook.HookEngine;
import com.bluecode.hook.Payload;
import com.bluecode.llm.LlmClient;
import com.bluecode.llm.PromptTooLongException;
import com.bluecode.llm.Request;
import com.bluecode.llm.StreamEvent;
import com.bluecode.llm.SystemPrompt;
import com.bluecode.llm.ToolCall;
import com.bluecode.llm.ToolResult;
import com.bluecode.memory.Manager;
import com.bluecode.permission.Decision;
import com.bluecode.permission.Mode;
import com.bluecode.permission.Outcome;
import com.bluecode.permission.PermissionEngine;
import com.bluecode.prompt.Environment;
import com.bluecode.prompt.PromptBuilder;
import com.bluecode.prompt.Reminder;
import com.bluecode.skill.SkillCatalog;
import com.bluecode.tool.Registry;
import com.bluecode.tool.Result;
import com.bluecode.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public final class Agent {
    static final int MAX_ITERATIONS = 25;
    static final int MAX_UNKNOWN_RUN = 3;
    static final int PLAN_REMINDER_INTERVAL = 4;
    static final String NOTICE_MAX_ITER = "(已达到最大迭代轮数 25，自动停止；可继续发送消息推进。)";
    static final String NOTICE_UNKNOWN_TOOLS = "(连续多轮只请求到未注册的工具，自动停止。)";
    static final String NOTICE_STREAM_ERR = "(请求出错，本轮已中断。)";
    static final String NOTICE_CANCELLED = "(已取消。)";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EMPTY_FINAL = "(模型没有返回文本。)";
    private static final ThreadLocal<RunContext> CURRENT_CONTEXT = new ThreadLocal<>();

    private final LlmClient client;
    private final Registry registry;
    private final String version;
    private final PermissionEngine engine;
    private final SessionRuntime runtime;
    private final Manager memoryManager;
    private final String instructionText;
    private final String memoryText;
    private final SkillCatalog skillCatalog;
    private final String systemPrompt;
    private final int maxTurns;
    private final Mode permissionMode;
    private final boolean permissionModeSet;
    private final boolean dontAsk;
    private final ApprovalUpgrader approvalUpgrader;
    private final boolean subAgent;
    private final HookEngine hookEngine;
    private final ReentrantLock runLock = new ReentrantLock();
    private volatile ToolContext toolContext;

    public Agent(LlmClient client, Registry registry) {
        this(client, registry, "dev");
    }

    public Agent(LlmClient client, Registry registry, String version) {
        this(client, registry, version, PermissionEngine.create(Path.of("").toAbsolutePath()));
    }

    public Agent(LlmClient client, Registry registry, String version, PermissionEngine engine) {
        this(client, registry, version, engine, SessionRuntime.empty(200000));
    }

    public Agent(LlmClient client, Registry registry, String version, PermissionEngine engine, SessionRuntime runtime) {
        this(client, registry, version, engine, runtime, null, "", "");
    }

    public Agent(
            LlmClient client,
            Registry registry,
            String version,
            PermissionEngine engine,
            SessionRuntime runtime,
            Manager memoryManager,
            String instructionText,
            String memoryText) {
        this(client, registry, version, engine, runtime, memoryManager, instructionText, memoryText,
                null, "", 0, Mode.DEFAULT, false, false, null, false);
    }

    public Agent(
            LlmClient client,
            Registry registry,
            String version,
            PermissionEngine engine,
            SessionRuntime runtime,
            Manager memoryManager,
            String instructionText,
            String memoryText,
            SkillCatalog skillCatalog,
            String systemPrompt,
            int maxTurns,
            Mode permissionMode,
            boolean permissionModeSet,
            boolean dontAsk,
            ApprovalUpgrader approvalUpgrader,
            boolean subAgent) {
        this(client, registry, version, engine, runtime, memoryManager, instructionText, memoryText,
                skillCatalog, systemPrompt, maxTurns, permissionMode, permissionModeSet, dontAsk,
                approvalUpgrader, subAgent, null);
    }

    public Agent(
            LlmClient client,
            Registry registry,
            String version,
            PermissionEngine engine,
            SessionRuntime runtime,
            Manager memoryManager,
            String instructionText,
            String memoryText,
            SkillCatalog skillCatalog,
            String systemPrompt,
            int maxTurns,
            Mode permissionMode,
            boolean permissionModeSet,
            boolean dontAsk,
            ApprovalUpgrader approvalUpgrader,
            boolean subAgent,
            HookEngine hookEngine) {
        this(client, registry, version, engine, runtime, memoryManager, instructionText, memoryText,
                skillCatalog, systemPrompt, maxTurns, permissionMode, permissionModeSet, dontAsk,
                approvalUpgrader, subAgent, hookEngine, ToolContext.root());
    }

    public Agent(
            LlmClient client,
            Registry registry,
            String version,
            PermissionEngine engine,
            SessionRuntime runtime,
            Manager memoryManager,
            String instructionText,
            String memoryText,
            SkillCatalog skillCatalog,
            String systemPrompt,
            int maxTurns,
            Mode permissionMode,
            boolean permissionModeSet,
            boolean dontAsk,
            ApprovalUpgrader approvalUpgrader,
            boolean subAgent,
            HookEngine hookEngine,
            ToolContext toolContext) {
        this.client = client;
        this.registry = registry;
        this.version = version == null || version.isBlank() ? "dev" : version;
        this.engine = engine == null ? PermissionEngine.create(Path.of("").toAbsolutePath()) : engine;
        this.runtime = runtime == null ? SessionRuntime.empty(200000) : runtime;
        this.memoryManager = memoryManager;
        this.instructionText = instructionText == null ? "" : instructionText;
        this.memoryText = memoryText == null ? "" : memoryText;
        this.skillCatalog = skillCatalog;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.maxTurns = Math.max(0, maxTurns);
        this.permissionMode = permissionMode == null ? Mode.DEFAULT : permissionMode;
        this.permissionModeSet = permissionModeSet;
        this.dontAsk = dontAsk;
        this.approvalUpgrader = approvalUpgrader;
        this.subAgent = subAgent;
        this.hookEngine = hookEngine;
        this.toolContext = toolContext == null ? ToolContext.root() : toolContext;
        this.runtime.hookEngine = hookEngine;
    }

    public static Builder builder() {
        return new Builder();
    }

    public BlockingQueue<Event> run(ConversationManager conversation) {
        return run(conversation, Mode.DEFAULT, new CancelToken());
    }

    public BlockingQueue<Event> run(ConversationManager conversation, Mode mode, CancelToken cancel) {
        BlockingQueue<Event> out = new LinkedBlockingQueue<>();
        CancelToken effectiveCancel = cancel == null ? new CancelToken() : cancel;
        Mode effectiveMode = effectiveModeFor(mode);
        Thread.startVirtualThread(() -> {
            runLock.lock();
            try {
                runLoop(conversation, effectiveMode, effectiveCancel, out, true, MAX_ITERATIONS);
            } finally {
                runLock.unlock();
            }
        });
        return out;
    }

    public String runToCompletion(CancelToken cancel, ConversationManager conversation, String task,
                                  BlockingQueue<Event> events) throws InterruptedException {
        if (conversation == null) {
            throw new IllegalArgumentException("conversation 不能为空");
        }
        if (task != null && !task.isBlank()) {
            conversation.addUserMessage(task);
        }
        BlockingQueue<Event> out = events == null ? new LinkedBlockingQueue<>() : events;
        CancelToken effectiveCancel = cancel == null ? new CancelToken() : cancel;
        runLock.lockInterruptibly();
        try {
            runLoop(conversation, effectiveModeFor(Mode.DEFAULT), effectiveCancel, out, false, effectiveMaxTurns());
        } finally {
            runLock.unlock();
        }
        return lastAssistantText(conversation).orElse(EMPTY_FINAL);
    }

    public LlmClient client() {
        return client;
    }

    public Registry registry() {
        return registry;
    }

    public void activateSkill(String name, String body) {
        runtime.activeSkills.activate(name, body);
    }

    public void clearActiveSkills() {
        runtime.activeSkills.clear();
    }

    public String version() {
        return version;
    }

    public PermissionEngine engine() {
        return engine;
    }

    public ToolContext toolContext() {
        return toolContext == null ? ToolContext.root() : toolContext;
    }

    public void setToolContext(ToolContext toolContext) {
        this.toolContext = toolContext == null ? ToolContext.root() : toolContext;
    }

    public int contextWindow() {
        return runtime.contextWindow;
    }

    public static Optional<RunContext> currentRunContext() {
        return Optional.ofNullable(CURRENT_CONTEXT.get());
    }

    public ForceCompactResult runForceCompact(ConversationManager conversation, List<Map<String, Object>> toolDefinitions) {
        runLock.lock();
        try {
            long anchor = runtime.getUsageAnchor();
            int anchorLen = runtime.getAnchorMsgLen();
            long estimated = Token.estimateTokens(anchor, conversation.getMessages(), anchorLen);
            ContextCompactor.Input input = compactInput(
                    conversation,
                    toolDefinitions,
                    anchor,
                    anchorLen,
                    estimated,
                    ContextCompactor.TriggerKind.MANUAL);
            try {
                dispatchHook(com.bluecode.hook.Event.PRE_COMPACT, Mode.DEFAULT, Map.of("trigger", "manual"));
                ContextCompactor.Output output = ContextCompactor.manage(input);
                dispatchHook(com.bluecode.hook.Event.POST_COMPACT, Mode.DEFAULT, Map.of(
                        "after_tokens", output.afterTokens(),
                        "before_tokens", output.beforeTokens(),
                        "trigger", "manual"));
                runtime.updateAnchor(0, 0);
                return new ForceCompactResult(output.beforeTokens(), output.afterTokens(), null);
            } catch (CompactException e) {
                return new ForceCompactResult(estimated, estimated, e);
            }
        } finally {
            runLock.unlock();
        }
    }

    private void runLoop(ConversationManager conversation, Mode mode, CancelToken cancel, BlockingQueue<Event> out,
                         boolean updateMemory, int maxIterations) {
        RunContext previous = CURRENT_CONTEXT.get();
        CURRENT_CONTEXT.set(new RunContext(this, conversation, subAgent));
        try {
            String stableSystem = systemPrompt.isBlank()
                    ? PromptBuilder.buildSystemPrompt(instructionText, currentMemoryText(), skillsCatalogText())
                    : systemPrompt;
            int unknownRun = 0;

            for (int iter = 1; iter <= maxIterations; iter++) {
                if (cancel.isCancelled()) {
                    finishCancelled(conversation, out);
                    return;
                }
                out.offer(new Event.Iter(iter));

                List<Map<String, Object>> tools = mode == Mode.PLAN ? registry.readOnlyDefinitions() : registry.definitions();
                String environment = environmentText();
                long anchor = runtime.getUsageAnchor();
                int anchorLen = runtime.getAnchorMsgLen();
                long estimated = Token.estimateTokens(anchor, conversation.getMessages(), anchorLen);
                boolean willAutoCompact = willAutoCompact(estimated);
                ContextCompactor.Input autoInput = compactInput(
                        conversation,
                        tools,
                        anchor,
                        anchorLen,
                        estimated,
                        ContextCompactor.TriggerKind.AUTO);
                if (willAutoCompact) {
                    out.offer(new CompactEvent(CompactPhase.BEFORE_AUTO, 0, 0, null));
                }
                ContextCompactor.Output compactOutput;
                try {
                    dispatchHook(com.bluecode.hook.Event.PRE_COMPACT, mode, Map.of("trigger", "auto"));
                    compactOutput = ContextCompactor.manage(autoInput);
                    dispatchHook(com.bluecode.hook.Event.POST_COMPACT, mode, Map.of(
                            "after_tokens", compactOutput.afterTokens(),
                            "before_tokens", compactOutput.beforeTokens(),
                            "trigger", "auto"));
                } catch (CompactException e) {
                    if (willAutoCompact) {
                        out.offer(new CompactEvent(CompactPhase.AFTER_AUTO, estimated, estimated, e));
                    }
                    offerFailed(out, mode, "上下文压缩失败: " + e.getMessage());
                    ensureAssistantTail(conversation, NOTICE_STREAM_ERR);
                    return;
                }
                if (willAutoCompact) {
                    out.offer(new CompactEvent(
                            CompactPhase.AFTER_AUTO,
                            compactOutput.beforeTokens(),
                            compactOutput.afterTokens(),
                            null));
                }

                String reminder = mode == Mode.PLAN ? Reminder.plan(iter == 1 || (iter - 1) % PLAN_REMINDER_INTERVAL == 0) : "";
                StreamPass pass;
                boolean emergencyRetried = false;
                while (true) {
                    try {
                        pass = streamOnce(conversation, tools, stableSystem, environment, reminder, mode, out, cancel);
                        break;
                    } catch (StreamException e) {
                        if (e.getCause() instanceof PromptTooLongException && !emergencyRetried) {
                            out.offer(new CompactEvent(CompactPhase.BEFORE_EMERGENCY, 0, 0, null));
                            long emergencyEstimated = Token.estimateTokens(
                                    runtime.getUsageAnchor(),
                                    conversation.getMessages(),
                                    runtime.getAnchorMsgLen());
                            ContextCompactor.Input emergencyInput = compactInput(
                                    conversation,
                                    tools,
                                    runtime.getUsageAnchor(),
                                    runtime.getAnchorMsgLen(),
                                    emergencyEstimated,
                                    ContextCompactor.TriggerKind.EMERGENCY);
                            ContextCompactor.Output emergencyOutput;
                            try {
                                dispatchHook(com.bluecode.hook.Event.PRE_COMPACT, mode, Map.of("trigger", "emergency"));
                                emergencyOutput = ContextCompactor.manage(emergencyInput);
                                dispatchHook(com.bluecode.hook.Event.POST_COMPACT, mode, Map.of(
                                        "after_tokens", emergencyOutput.afterTokens(),
                                        "before_tokens", emergencyOutput.beforeTokens(),
                                        "trigger", "emergency"));
                                out.offer(new CompactEvent(
                                        CompactPhase.AFTER_EMERGENCY,
                                        emergencyOutput.beforeTokens(),
                                        emergencyOutput.afterTokens(),
                                        null));
                            } catch (CompactException compactError) {
                                out.offer(new CompactEvent(
                                        CompactPhase.AFTER_EMERGENCY,
                                        emergencyEstimated,
                                        emergencyEstimated,
                                        compactError));
                                offerFailed(out, mode, "紧急压缩失败: " + compactError.getMessage());
                                ensureAssistantTail(conversation, NOTICE_STREAM_ERR);
                                return;
                            }
                            runtime.updateAnchor(0, 0);
                            long afterEmergency = Token.estimateTokens(0, conversation.getMessages(), 0);
                            if (afterEmergency >= runtime.contextWindow - CompactConstants.MANUAL_SAFETY_MARGIN) {
                                offerFailed(out, mode, "紧急压缩后上下文仍然过长");
                                ensureAssistantTail(conversation, NOTICE_STREAM_ERR);
                                return;
                            }
                            emergencyRetried = true;
                            continue;
                        }
                        offerFailed(out, mode, e.getMessage());
                        ensureAssistantTail(conversation, NOTICE_STREAM_ERR);
                        return;
                    }
                }
                if (pass.cancelled()) {
                    finishCancelled(conversation, out);
                    return;
                }
                if (pass.usage() != null) {
                    out.offer(new Event.UsageReport(new Usage(pass.usage().inputTokens(), pass.usage().outputTokens(),
                            pass.usage().cacheWrite(), pass.usage().cacheRead())));
                }
                if (pass.calls().isEmpty()) {
                    String finalText = ensureFinal(out, pass.text());
                    conversation.addAssistantMessage(finalText);
                    updateUsageAnchor(pass, conversation);
                    if (updateMemory) {
                        triggerMemoryUpdate(conversation);
                    }
                    dispatchHook(com.bluecode.hook.Event.STOP, mode, Map.of("iter", iter));
                    out.offer(new Event.Done());
                    return;
                }

                conversation.addAssistantToolCallMessage(pass.text(), pass.calls());
                updateUsageAnchor(pass, conversation);
                unknownRun = allUnknown(pass.calls()) ? unknownRun + 1 : 0;

                BatchOutcome batch = executeBatched(pass.calls(), mode, cancel, out);
                conversation.addToolResults(batch.results());
                if (!batch.completed()) {
                    out.offer(new Event.Notice(NOTICE_CANCELLED));
                    ensureAssistantTail(conversation, NOTICE_CANCELLED);
                    out.offer(new Event.Done());
                    return;
                }
                if (unknownRun >= MAX_UNKNOWN_RUN) {
                    out.offer(new Event.Notice(NOTICE_UNKNOWN_TOOLS));
                    ensureAssistantTail(conversation, NOTICE_UNKNOWN_TOOLS);
                    out.offer(new Event.Done());
                    return;
                }
            }

            String notice = maxIterations == MAX_ITERATIONS
                    ? NOTICE_MAX_ITER
                    : "(已达到最大迭代轮数 " + maxIterations + "，自动停止。)";
            out.offer(new Event.Notice(notice));
            ensureAssistantTail(conversation, notice);
            out.offer(new Event.Done());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ensureAssistantTail(conversation, NOTICE_CANCELLED);
            offerFailed(out, mode, "会话被中断");
        } catch (Exception e) {
            ensureAssistantTail(conversation, NOTICE_STREAM_ERR);
            offerFailed(out, mode, "Agent 执行失败: " + e.getMessage());
        } finally {
            if (previous == null) {
                CURRENT_CONTEXT.remove();
            } else {
                CURRENT_CONTEXT.set(previous);
            }
        }
    }

    private StreamPass streamOnce(ConversationManager conversation, List<Map<String, Object>> tools, String stableSystem,
                                  String environment, String reminder, Mode mode,
                                  BlockingQueue<Event> out, CancelToken cancel)
            throws InterruptedException, StreamException {
        dispatchHook(com.bluecode.hook.Event.PRE_USER_MESSAGE, mode, Map.of("prompt", lastUserPrompt(conversation)));
        Request request = new Request(
                conversation.getMessages(),
                tools,
                new SystemPrompt(stableSystem, environment),
                composeReminder(reminder));
        BlockingQueue<StreamEvent> stream = client.stream(request);
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        com.bluecode.llm.Usage usage = null;

        while (true) {
            if (cancel.isCancelled()) {
                return StreamPass.cancelled(text.toString(), calls, usage);
            }
            StreamEvent event = stream.poll(100, TimeUnit.MILLISECONDS);
            if (event == null) {
                continue;
            }
            switch (event) {
                case StreamEvent.TextDelta delta -> {
                    text.append(delta.text());
                    out.offer(new Event.Text(delta.text()));
                }
                case StreamEvent.ThinkingDelta ignored -> {
                }
                case StreamEvent.ToolCallStart ignored -> {
                }
                case StreamEvent.ToolCallDelta ignored -> {
                }
                case StreamEvent.ToolCallComplete complete ->
                        calls.add(new ToolCall(complete.toolCallId(), complete.toolName(), complete.arguments()));
                case StreamEvent.UsageEvent usageEvent -> usage = usageEvent.usage();
                case StreamEvent.StreamEnd end -> {
                    if (usage == null && (end.inputTokens() > 0 || end.outputTokens() > 0)) {
                        usage = new com.bluecode.llm.Usage(end.inputTokens(), end.outputTokens(), 0, 0);
                    }
                    return new StreamPass(text.toString(), List.copyOf(calls), usage, false);
                }
                case StreamEvent.Error error -> {
                    throw new StreamException(error.message(), error.cause());
                }
            }
        }
    }

    private BatchOutcome executeBatched(List<ToolCall> calls, Mode mode, CancelToken cancel, BlockingQueue<Event> out)
            throws InterruptedException {
        ToolResult[] results = new ToolResult[calls.size()];
        int i = 0;
        while (i < calls.size()) {
            if (cancel.isCancelled()) {
                fillCancelled(calls, results, i, calls.size());
                return new BatchOutcome(copyResults(results), false);
            }

            ToolCall current = calls.get(i);
            if (registry.isReadOnly(current.name())) {
                int start = i;
                int end = i + 1;
                while (end < calls.size() && registry.isReadOnly(calls.get(end).name())) {
                    end++;
                }
                List<Integer> allowed = new ArrayList<>();
                for (int k = start; k < end; k++) {
                    ToolCall call = calls.get(k);
                    DispatchResult preTool = dispatchPreTool(mode, call);
                    emitToolStart(out, call);
                    if (preTool.blocked()) {
                        results[k] = hookBlocked(call, preTool);
                        continue;
                    }
                    PermissionEngine.CheckResult check = engine.check(mode, call, true);
                    if (check.decision() == Decision.DENY) {
                        results[k] = denied(call, check.reason());
                    } else {
                        allowed.add(k);
                    }
                }
                CountDownLatch latch = new CountDownLatch(allowed.size());
                for (int index : allowed) {
                    Thread.startVirtualThread(() -> {
                        try {
                            results[index] = executeOne(calls.get(index));
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                    if (cancel.isCancelled()) {
                        fillCancelled(calls, results, start, end);
                        emitToolEnds(out, calls, results, start, end, mode);
                        fillCancelled(calls, results, end, calls.size());
                        return new BatchOutcome(copyResults(results), false);
                    }
                }
                emitToolEnds(out, calls, results, start, end, mode);
                i = end;
            } else {
                DispatchResult preTool = dispatchPreTool(mode, current);
                if (preTool.blocked()) {
                    emitToolStart(out, current);
                    results[i] = hookBlocked(current, preTool);
                    dispatchPostTool(mode, current, results[i]);
                    emitToolEnd(out, current, results[i]);
                    i++;
                    continue;
                }
                PermissionEngine.CheckResult check = engine.check(mode, current, false);
                if (check.decision() == Decision.DENY) {
                    emitToolStart(out, current);
                    results[i] = denied(current, check.reason());
                    dispatchPostTool(mode, current, results[i]);
                    emitToolEnd(out, current, results[i]);
                    i++;
                    continue;
                }
                if (check.decision() == Decision.ASK) {
                    if (dontAsk) {
                        emitToolStart(out, current);
                        results[i] = executeOne(current);
                        dispatchPostTool(mode, current, results[i]);
                        emitToolEnd(out, current, results[i]);
                        i++;
                        continue;
                    }
                    Outcome outcome = requestApproval(current, check.reason(), mode, cancel, out);
                    if (outcome == null) {
                        fillCancelled(calls, results, i, calls.size());
                        return new BatchOutcome(copyResults(results), false);
                    }
                    if (outcome == Outcome.DENY_ONCE) {
                        emitToolStart(out, current);
                        results[i] = denied(current, check.reason());
                        dispatchPostTool(mode, current, results[i]);
                        emitToolEnd(out, current, results[i]);
                        i++;
                        continue;
                    }
                    if (outcome == Outcome.ALLOW_FOREVER) {
                        try {
                            engine.persistLocalAllow(current);
                        } catch (IOException e) {
                            out.offer(new Event.Notice("永久允许规则写入失败，本次仍继续执行: " + e.getMessage()));
                        }
                    }
                }
                emitToolStart(out, current);
                results[i] = executeOne(current);
                dispatchPostTool(mode, current, results[i]);
                emitToolEnd(out, current, results[i]);
                i++;
                if (cancel.isCancelled()) {
                    fillCancelled(calls, results, i, calls.size());
                    return new BatchOutcome(copyResults(results), false);
                }
            }
        }
        return new BatchOutcome(copyResults(results), true);
    }

    private Outcome requestApproval(ToolCall call, String reason, Mode mode, CancelToken cancel, BlockingQueue<Event> out)
            throws InterruptedException {
        BlockingQueue<Outcome> respond = new ArrayBlockingQueue<>(1);
        ApprovalRequest request = new ApprovalRequest(call.name(), previewArgs(call.arguments()), reason, respond);
        if (approvalUpgrader != null) {
            Optional<Outcome> upgraded = approvalUpgrader.upgrade(cancel, request);
            if (upgraded.isPresent()) {
                return upgraded.get();
            }
        }
        dispatchHook(com.bluecode.hook.Event.NOTIFICATION, mode, Map.of(
                "detail", call.name(),
                "kind", "approval"));
        out.offer(new Event.Approval(request));
        while (!cancel.isCancelled()) {
            Outcome outcome = respond.poll(100, TimeUnit.MILLISECONDS);
            if (outcome != null) {
                return outcome;
            }
        }
        return null;
    }

    private ToolResult executeOne(ToolCall call) {
        Result result = registry.execute(toolContext(), call.name(), call.arguments());
        recordReadFile(call, result);
        return new ToolResult(call.id(), result.content(), result.isError());
    }

    private void recordReadFile(ToolCall call, Result result) {
        if (!"ReadFile".equals(call.name()) || result == null || result.isError()) {
            return;
        }
        try {
            Map<String, Object> args = MAPPER.readValue(call.arguments(), new TypeReference<>() {
            });
            Object pathObject = args.get("path");
            if (!(pathObject instanceof String path) || path.isBlank()) {
                return;
            }
            Path absolute = toolContext().resolvePath(path);
            String content = Files.readString(absolute, StandardCharsets.UTF_8);
            runtime.recovery.recordFile(absolute.toString(), content);
        } catch (Exception ignored) {
            // 文件追踪是恢复增强能力,失败时不影响工具结果回填。
        }
    }

    private ToolResult denied(ToolCall call, String reason) {
        return new ToolResult(call.id(), reason == null || reason.isBlank() ? "权限拒绝" : reason, true);
    }

    private DispatchResult dispatchPreTool(Mode mode, ToolCall call) {
        return dispatchHook(com.bluecode.hook.Event.PRE_TOOL_USE, mode, Map.of(
                "tool_input", toolInput(call),
                "tool_name", hookToolName(call.name())));
    }

    private void dispatchPostTool(Mode mode, ToolCall call, ToolResult result) {
        dispatchHook(com.bluecode.hook.Event.POST_TOOL_USE, mode, Map.of(
                "is_error", result == null || result.isError(),
                "tool_input", toolInput(call),
                "tool_name", hookToolName(call.name()),
                "tool_result", result == null ? "" : result.content()));
    }

    private ToolResult hookBlocked(ToolCall call, DispatchResult result) {
        String hookName = result.blockingHookName().isBlank() ? "unknown" : result.blockingHookName();
        String reason = result.reason().isBlank() ? "blocked by hook" : result.reason();
        return new ToolResult(call.id(), "[hook " + hookName + "] " + reason, true);
    }

    private String hookToolName(String name) {
        String normalized = name == null ? "" : name.strip().toLowerCase();
        return switch (normalized) {
            case "readfile", "read_file" -> "read_file";
            case "writefile", "write_file" -> "write_file";
            case "editfile", "edit_file" -> "edit_file";
            case "bash" -> "bash";
            case "glob" -> "glob";
            case "grep" -> "grep";
            default -> name == null ? "" : name;
        };
    }

    private void emitToolStart(BlockingQueue<Event> out, ToolCall call) {
        out.offer(new Event.Tool(new ToolEvent(call.name(), previewArgs(call.arguments()), Phase.START, "", false)));
    }

    private void emitToolEnd(BlockingQueue<Event> out, ToolCall call, ToolResult result) {
        out.offer(new Event.Tool(new ToolEvent(call.name(), previewArgs(call.arguments()), Phase.END,
                result.content(), result.isError())));
    }

    private void emitToolEnds(BlockingQueue<Event> out, List<ToolCall> calls, ToolResult[] results, int start, int end,
                              Mode mode) {
        for (int k = start; k < end; k++) {
            dispatchPostTool(mode, calls.get(k), results[k]);
            emitToolEnd(out, calls.get(k), results[k]);
        }
    }

    private void fillCancelled(List<ToolCall> calls, ToolResult[] results, int start, int end) {
        for (int k = start; k < end; k++) {
            if (results[k] == null) {
                results[k] = new ToolResult(calls.get(k).id(), NOTICE_CANCELLED, true);
            }
        }
    }

    private List<ToolResult> copyResults(ToolResult[] results) {
        for (int i = 0; i < results.length; i++) {
            if (results[i] == null) {
                results[i] = new ToolResult("cancelled-" + i, NOTICE_CANCELLED, true);
            }
        }
        return List.copyOf(Arrays.asList(results));
    }

    private boolean allUnknown(List<ToolCall> calls) {
        return !calls.isEmpty() && calls.stream().allMatch(call -> registry.get(call.name()).isEmpty());
    }

    private boolean willAutoCompact(long estimated) {
        if (runtime.contextWindow <= CompactConstants.SUMMARY_RESERVE + CompactConstants.AUTO_SAFETY_MARGIN) {
            return false;
        }
        return estimated >= runtime.contextWindow - CompactConstants.SUMMARY_RESERVE - CompactConstants.AUTO_SAFETY_MARGIN;
    }

    private Mode effectiveModeFor(Mode requested) {
        return permissionModeSet ? permissionMode : (requested == null ? Mode.DEFAULT : requested);
    }

    private int effectiveMaxTurns() {
        return maxTurns > 0 ? maxTurns : MAX_ITERATIONS;
    }

    private Optional<String> lastAssistantText(ConversationManager conversation) {
        List<Message> messages = conversation.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.role() == Message.Role.ASSISTANT && !message.content().isBlank()) {
                return Optional.of(message.content());
            }
        }
        return Optional.empty();
    }

    private ContextCompactor.Input compactInput(
            ConversationManager conversation,
            List<Map<String, Object>> tools,
            long anchor,
            int anchorLen,
            long estimated,
            ContextCompactor.TriggerKind trigger) {
        return new ContextCompactor.Input(
                conversation,
                client,
                client.model(),
                runtime.contextWindow,
                tools,
                runtime.replacement,
                runtime.recovery,
                runtime.autoTracking,
                runtime.session,
                anchor,
                anchorLen,
                estimated,
                trigger);
    }

    private void updateUsageAnchor(StreamPass pass, ConversationManager conversation) {
        if (pass.usage() != null) {
            runtime.updateAnchor(Token.usageAnchor(pass.usage()), conversation.size());
        }
    }

    private String currentMemoryText() {
        if (memoryManager == null) {
            return memoryText;
        }
        String loaded = memoryManager.loadIndex();
        return loaded.isBlank() ? memoryText : loaded;
    }

    private String skillsCatalogText() {
        return skillCatalog == null ? "" : skillCatalog.buildCatalogContext();
    }

    private String environmentText() {
        String base = Environment.gather(version, client.model()).render();
        String active = runtime.activeSkills.renderActiveContext();
        return active.isBlank() ? base : base + "\n\n" + active;
    }

    private DispatchResult dispatchHook(com.bluecode.hook.Event event, Mode mode, Map<String, Object> extras) {
        if (hookEngine == null) {
            return DispatchResult.empty();
        }
        DispatchResult result = hookEngine.dispatch(event, hookPayload(event, mode, extras));
        runtime.appendReminders(result.injectedPrompts());
        return result;
    }

    private Payload hookPayload(com.bluecode.hook.Event event, Mode mode, Map<String, Object> extras) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cwd", workspaceRoot());
        data.put("event", event.wireName());
        data.put("mode", (mode == null ? Mode.DEFAULT : mode).name().toLowerCase());
        data.put("session_id", runtime.session == null ? "" : runtime.session.sessionId());
        if (extras != null) {
            data.putAll(extras);
        }
        return new Payload(data);
    }

    private String composeReminder(String baseReminder) {
        List<String> injected = runtime.takeReminders();
        if (injected.isEmpty()) {
            return baseReminder == null ? "" : baseReminder;
        }
        String joined = String.join("\n\n", injected);
        if (baseReminder == null || baseReminder.isBlank()) {
            return joined;
        }
        return baseReminder + "\n\n" + joined;
    }

    private String workspaceRoot() {
        try {
            Path sessionDir = runtime.session.sessionDir();
            Path root = sessionDir.getParent().getParent().getParent();
            return root.toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return Path.of("").toAbsolutePath().normalize().toString();
        }
    }

    private String lastUserPrompt(ConversationManager conversation) {
        List<Message> messages = conversation.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message.role() == Message.Role.USER) {
                return message.content();
            }
        }
        return "";
    }

    private Map<String, Object> toolInput(ToolCall call) {
        try {
            return MAPPER.readValue(call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments(),
                    new TypeReference<>() {
                    });
        } catch (Exception e) {
            return Map.of("_raw", call.arguments() == null ? "" : call.arguments());
        }
    }

    private void triggerMemoryUpdate(ConversationManager conversation) {
        if (memoryManager == null) {
            return;
        }
        List<Message> recent = recentTurn(conversation.getMessages());
        long turns = runtime.incrementTurnCount();
        if (turns % 5 == 0 || hasMemorySignal(recent)) {
            memoryManager.updateAsync(recent);
        }
    }

    private List<Message> recentTurn(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = messages.size() - 1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == Message.Role.USER) {
                start = i;
                break;
            }
        }
        return List.copyOf(messages.subList(start, messages.size()));
    }

    private boolean hasMemorySignal(List<Message> messages) {
        String joined = messages == null ? "" : messages.stream()
                .filter(message -> message.role() == Message.Role.USER)
                .map(Message::content)
                .reduce("", (left, right) -> left + "\n" + right)
                .toLowerCase();
        return joined.contains("记住")
                || joined.contains("记忆")
                || joined.contains("别忘")
                || joined.contains("remember")
                || joined.contains("memo");
    }

    private String ensureFinal(BlockingQueue<Event> out, String text) {
        if (text != null && !text.isBlank()) {
            return text;
        }
        out.offer(new Event.Text(EMPTY_FINAL));
        return EMPTY_FINAL;
    }

    private void ensureAssistantTail(ConversationManager conversation, String fallback) {
        if (conversation.lastRole().orElse(null) != Message.Role.ASSISTANT) {
            conversation.addAssistantMessage(fallback);
        }
    }

    private void finishCancelled(ConversationManager conversation, BlockingQueue<Event> out) {
        out.offer(new Event.Notice(NOTICE_CANCELLED));
        ensureAssistantTail(conversation, NOTICE_CANCELLED);
        out.offer(new Event.Done());
    }

    private void offerFailed(BlockingQueue<Event> out, Mode mode, String message) {
        String detail = message == null || message.isBlank() ? "请求出错" : message;
        dispatchHook(com.bluecode.hook.Event.NOTIFICATION, mode, Map.of(
                "detail", detail,
                "kind", "stream_error"));
        out.offer(new Event.Failed(detail));
    }

    private String previewArgs(String arguments) {
        try {
            JsonNode root = MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
            for (String key : List.of("path", "command", "pattern", "glob")) {
                JsonNode value = root.path(key);
                if (value.isTextual()) {
                    return truncate(key + "=" + value.asText(), 80);
                }
            }
        } catch (Exception ignored) {
            // 参数不是合法 JSON 时退回原始片段预览。
        }
        return truncate(arguments == null ? "{}" : arguments, 80);
    }

    private String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 1) + "...";
    }

    public record ForceCompactResult(long before, long after, Throwable error) {
    }

    public record RunContext(Agent agent, ConversationManager conversation, boolean subAgent) {
    }

    private record StreamPass(String text, List<ToolCall> calls, com.bluecode.llm.Usage usage, boolean cancelled) {
        static StreamPass cancelled(String text, List<ToolCall> calls, com.bluecode.llm.Usage usage) {
            return new StreamPass(text, List.copyOf(calls), usage, true);
        }
    }

    private record BatchOutcome(List<ToolResult> results, boolean completed) {
    }

    private static final class StreamException extends Exception {
        private StreamException(String message, Throwable cause) {
            super(message == null || message.isBlank() ? "请求出错" : message, cause);
        }
    }

    public static final class Builder {
        private LlmClient client;
        private Registry registry;
        private String version = "dev";
        private PermissionEngine engine;
        private SessionRuntime runtime;
        private Manager memoryManager;
        private String instructionText = "";
        private String memoryText = "";
        private SkillCatalog skillCatalog;
        private String systemPrompt = "";
        private int maxTurns;
        private Mode permissionMode = Mode.DEFAULT;
        private boolean permissionModeSet;
        private boolean dontAsk;
        private ApprovalUpgrader approvalUpgrader;
        private boolean subAgent;
        private HookEngine hookEngine;
        private ToolContext toolContext = ToolContext.root();

        public Builder client(LlmClient client) {
            this.client = client;
            return this;
        }

        public Builder provider(LlmClient client) {
            return client(client);
        }

        public Builder registry(Registry registry) {
            this.registry = registry;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder engine(PermissionEngine engine) {
            this.engine = engine;
            return this;
        }

        public Builder runtime(SessionRuntime runtime) {
            this.runtime = runtime;
            return this;
        }

        public Builder memoryManager(Manager memoryManager) {
            this.memoryManager = memoryManager;
            return this;
        }

        public Builder instructionText(String instructionText) {
            this.instructionText = instructionText;
            return this;
        }

        public Builder memoryText(String memoryText) {
            this.memoryText = memoryText;
            return this;
        }

        public Builder skillCatalog(SkillCatalog skillCatalog) {
            this.skillCatalog = skillCatalog;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = Math.max(0, maxTurns);
            return this;
        }

        public Builder permissionMode(Mode permissionMode) {
            this.permissionMode = permissionMode == null ? Mode.DEFAULT : permissionMode;
            this.permissionModeSet = true;
            return this;
        }

        public Builder dontAsk(boolean dontAsk) {
            this.dontAsk = dontAsk;
            return this;
        }

        public Builder approvalUpgrader(ApprovalUpgrader approvalUpgrader) {
            this.approvalUpgrader = approvalUpgrader;
            return this;
        }

        public Builder subAgent(boolean subAgent) {
            this.subAgent = subAgent;
            return this;
        }

        public Builder hookEngine(HookEngine hookEngine) {
            this.hookEngine = hookEngine;
            return this;
        }

        public Builder toolContext(ToolContext toolContext) {
            this.toolContext = toolContext == null ? ToolContext.root() : toolContext;
            return this;
        }

        public Agent build() {
            if (client == null) {
                throw new IllegalArgumentException("client 不能为空");
            }
            Registry effectiveRegistry = registry == null ? Registry.createDefault() : registry;
            SessionRuntime effectiveRuntime = runtime == null ? SessionRuntime.empty(200000) : runtime;
            return new Agent(
                    client,
                    effectiveRegistry,
                    version,
                    engine,
                    effectiveRuntime,
                    memoryManager,
                    instructionText,
                    memoryText,
                    skillCatalog,
                    systemPrompt,
                    maxTurns,
                    permissionMode,
                    permissionModeSet,
                    dontAsk,
                    approvalUpgrader,
                    subAgent,
                    hookEngine,
                    toolContext);
        }
    }
}
