package com.bluecode.tui;

import com.bluecode.agent.Agent;
import com.bluecode.agent.AgentTool;
import com.bluecode.agent.ApprovalRequest;
import com.bluecode.agent.CancelToken;
import com.bluecode.agent.CompactEvent;
import com.bluecode.agent.Event;
import com.bluecode.agent.Phase;
import com.bluecode.agent.SessionRuntime;
import com.bluecode.command.Builtins;
import com.bluecode.command.CommandRegistry;
import com.bluecode.command.Dispatch;
import com.bluecode.command.Kind;
import com.bluecode.command.SkillCommands;
import com.bluecode.command.Ui;
import com.bluecode.command.WorktreeAccessor;
import com.bluecode.compact.CompactConstants;
import com.bluecode.compact.Token;
import com.bluecode.compact.state.SessionContext;
import com.bluecode.config.ProviderConfig;
import com.bluecode.conversation.ConversationManager;
import com.bluecode.conversation.Message;
import com.bluecode.coordinator.Coordinator;
import com.bluecode.hook.DispatchResult;
import com.bluecode.hook.HookEngine;
import com.bluecode.hook.HookRule;
import com.bluecode.hook.Payload;
import com.bluecode.llm.LlmClient;
import com.bluecode.memory.Manager;
import com.bluecode.permission.Mode;
import com.bluecode.permission.Outcome;
import com.bluecode.permission.PermissionEngine;
import com.bluecode.prompt.Reminder;
import com.bluecode.session.SessionInfo;
import com.bluecode.session.SessionList;
import com.bluecode.session.SessionLoader;
import com.bluecode.session.Writer;
import com.bluecode.skill.InstallSkillTool;
import com.bluecode.skill.LoadSkillTool;
import com.bluecode.skill.SkillCatalog;
import com.bluecode.skill.SkillExecutor;
import com.bluecode.subagent.Catalog;
import com.bluecode.team.TeamManager;
import com.bluecode.tool.Registry;
import com.bluecode.tool.ToolContext;
import com.bluecode.tui.tea.Command;
import com.bluecode.tui.tea.CursorPlacement;
import com.bluecode.tui.tea.KeyPressMessage;
import com.bluecode.tui.tea.Model;
import com.bluecode.tui.tea.Program;
import com.bluecode.tui.tea.UpdateResult;
import com.bluecode.tui.tea.WindowSizeMessage;
import com.bluecode.worktree.WorktreeManager;
import com.bluecode.worktree.WorktreeSession;
import org.jline.utils.WCWidth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class bluecodeModel implements Model, Ui {
    private static final Logger LOGGER = Logger.getLogger(bluecodeModel.class.getName());
    private static final String VERSION = "0.2.0";

    private final List<ProviderConfig> providers;
    private final Registry registry;
    private final CommandRegistry cmdRegistry;
    private final SkillCatalog skillCatalog;
    private final CompletionMenu completion = new CompletionMenu();
    private final PermissionEngine engine;
    private final SessionRuntime runtime;
    private final Path cwd;
    private final Path sessionsDir;
    private final Manager memoryManager;
    private final String instructionText;
    private final com.bluecode.task.Manager taskManager;
    private final Catalog subAgentCatalog;
    private final HookEngine hookEngine;
    private final WorktreeManager worktreeMgr;
    private final TeamManager teamManager;
    private final boolean coordinatorMode;
    private final List<ChatMessage> chatMessages = new ArrayList<>();
    private final MarkdownRenderer markdownRenderer = new MarkdownRenderer();
    private final StringBuilder input = new StringBuilder();
    private final StringBuilder streamBuffer = new StringBuilder();
    private final List<ToolDisplay> currentTools = new ArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private ConversationManager conversation;
    private Writer sessionWriter;
    private Program program;
    private AppState state;
    private ProviderConfig activeProvider;
    private LlmClient client;
    private Agent agent;
    private String memoryText;
    private Mode mode = Mode.DEFAULT;
    private CancelToken turnCancel;
    private ApprovalRequest pendingApproval;
    private int approveCursor;
    private int selectedIndex;
    private final List<SessionInfo> resumeSessions = new ArrayList<>();
    private final List<SessionInfo> resumeFiltered = new ArrayList<>();
    private final StringBuilder resumeQuery = new StringBuilder();
    private int resumeCursor;
    private int width = 100;
    private int height = 30;
    private boolean streaming;
    private long streamStartedAt;
    private long finishedElapsedMillis;
    private long usageIn;
    private long usageOut;
    private int iter;
    private int spinnerFrame;
    private boolean sessionStartDispatched;
    private boolean sessionEndDispatched;
    private Command pendingUiCommand = Command.none();
    private String currentCommandArguments = "";
    private Path activeCwd;
    private WorktreeAccessor worktreeAccessor;

    public bluecodeModel(List<ProviderConfig> providers, Registry registry) {
        this(providers, registry, PermissionEngine.create(Path.of("").toAbsolutePath()));
    }

    public bluecodeModel(List<ProviderConfig> providers, Registry registry, PermissionEngine engine) {
        this(providers, registry, engine, SessionRuntime.empty(providers == null || providers.isEmpty()
                ? 200000
                : providers.getFirst().effectiveContextWindow()));
    }

    public bluecodeModel(
            List<ProviderConfig> providers,
            Registry registry,
            PermissionEngine engine,
            SessionRuntime runtime) {
        this(providers, registry, engine, runtime, null, null, "", "", Path.of("").toAbsolutePath().normalize());
    }

    public bluecodeModel(
            List<ProviderConfig> providers,
            Registry registry,
            PermissionEngine engine,
            SessionRuntime runtime,
            Writer sessionWriter,
            Manager memoryManager,
            String instructionText,
            String memoryText,
            Path cwd) {
        this(providers, registry, engine, runtime, sessionWriter, memoryManager, instructionText, memoryText, cwd,
                null, null);
    }

    public bluecodeModel(
            List<ProviderConfig> providers,
            Registry registry,
            PermissionEngine engine,
            SessionRuntime runtime,
            Writer sessionWriter,
            Manager memoryManager,
            String instructionText,
            String memoryText,
            Path cwd,
            com.bluecode.task.Manager taskManager,
            Catalog subAgentCatalog) {
        this(providers, registry, engine, runtime, sessionWriter, memoryManager, instructionText, memoryText, cwd,
                taskManager, subAgentCatalog, null);
    }

    public bluecodeModel(
            List<ProviderConfig> providers,
            Registry registry,
            PermissionEngine engine,
            SessionRuntime runtime,
            Writer sessionWriter,
            Manager memoryManager,
            String instructionText,
            String memoryText,
            Path cwd,
            com.bluecode.task.Manager taskManager,
            Catalog subAgentCatalog,
            HookEngine hookEngine) {
        this(providers, registry, engine, runtime, sessionWriter, memoryManager, instructionText, memoryText, cwd,
                taskManager, subAgentCatalog, hookEngine, null);
    }

    public bluecodeModel(
            List<ProviderConfig> providers,
            Registry registry,
            PermissionEngine engine,
            SessionRuntime runtime,
            Writer sessionWriter,
            Manager memoryManager,
            String instructionText,
            String memoryText,
            Path cwd,
            com.bluecode.task.Manager taskManager,
            Catalog subAgentCatalog,
            HookEngine hookEngine,
            WorktreeManager worktreeMgr) {
        this(providers, registry, engine, runtime, sessionWriter, memoryManager, instructionText, memoryText, cwd,
                taskManager, subAgentCatalog, hookEngine, worktreeMgr, null, false);
    }

    public bluecodeModel(
            List<ProviderConfig> providers,
            Registry registry,
            PermissionEngine engine,
            SessionRuntime runtime,
            Writer sessionWriter,
            Manager memoryManager,
            String instructionText,
            String memoryText,
            Path cwd,
            com.bluecode.task.Manager taskManager,
            Catalog subAgentCatalog,
            HookEngine hookEngine,
            WorktreeManager worktreeMgr,
            TeamManager teamManager,
            boolean coordinatorMode) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("providers 不能为空");
        }
        this.cwd = cwd == null ? Path.of("").toAbsolutePath().normalize() : cwd.toAbsolutePath().normalize();
        this.sessionsDir = this.cwd.resolve(".bluecode").resolve("sessions");
        this.sessionWriter = sessionWriter;
        this.memoryManager = memoryManager;
        this.instructionText = instructionText == null ? "" : instructionText;
        this.taskManager = taskManager;
        this.subAgentCatalog = subAgentCatalog;
        this.hookEngine = hookEngine;
        this.worktreeMgr = worktreeMgr;
        this.teamManager = teamManager;
        this.coordinatorMode = coordinatorMode;
        this.memoryText = memoryText == null ? "" : memoryText;
        this.conversation = sessionWriter == null
                ? new ConversationManager()
                : new ConversationManager(sessionWriter::onAppend, sessionWriter::onReplace);
        this.providers = List.copyOf(providers);
        this.registry = registry == null ? Registry.createDefault() : registry;
        CommandRegistry commands = new CommandRegistry();
        Builtins.registerAll(commands, teamManager);
        this.cmdRegistry = commands;
        this.engine = engine == null ? PermissionEngine.create(Path.of("").toAbsolutePath()) : engine;
        this.runtime = runtime == null ? SessionRuntime.empty(providers.getFirst().effectiveContextWindow()) : runtime;
        this.runtime.hookEngine = hookEngine;
        initializeWorktreeState();
        this.skillCatalog = new SkillCatalog().loadCatalog(this.cwd);
        registerSkillTools();
        SkillCommands.registerSkillListCommand(this.cmdRegistry, this.skillCatalog);
        SkillCommands.registerSkillsAsCommands(this.cmdRegistry, this.skillCatalog);
        this.mode = this.engine.startMode();
        if (providers.size() == 1) {
            initializeProvider(0);
            this.state = AppState.CHAT;
        } else {
            this.state = AppState.PROVIDER_SELECT;
        }
        startTaskDoneConsumer();
        startLeadMailWatcher();
    }

    public void setProgram(Program program) {
        this.program = program;
    }

    @Override
    public Command init() {
        dispatchSessionStart();
        return Command.checkWindowSize();
    }

    @Override
    public UpdateResult<? extends Model> update(com.bluecode.tui.tea.Message msg) {
        Command command = Command.none();
        switch (msg) {
            case WindowSizeMessage size -> {
                width = Math.max(40, size.width());
                height = Math.max(12, size.height());
            }
            case KeyPressMessage key -> command = handleKey(key);
            case AgentEventMessage agentEvent -> command = handleAgentEvent(agentEvent.event());
            case LeadMailEvent leadMail -> command = handleLeadMailEvent(leadMail);
            case TickMessage ignored -> {
                if (streaming) {
                    spinnerFrame++;
                    command = tick();
                }
            }
            default -> {
            }
        }
        return new UpdateResult<>(this, command);
    }

    @Override
    public String view() {
        StringBuilder view = new StringBuilder();
        view.append(renderBanner()).append('\n');
        if (state == AppState.PROVIDER_SELECT) {
            view.append(renderProviderSelect());
        } else {
            view.append(Styles.MUTED.render("Ready. 输入消息并按 Enter，Alt+Enter/Ctrl+J 换行，输入 /help 查看可用命令。")).append('\n');
            view.append(renderConversation());
            if (state == AppState.APPROVING && pendingApproval != null) {
                view.append(renderApproval());
            } else if (state == AppState.RESUME) {
                view.append(renderResume());
            } else if (streaming) {
                view.append(renderStreaming());
            }
            view.append(renderInputBox());
            if (completion.active()) {
                view.append(completion.render(width)).append('\n');
            }
            view.append(renderStatusBar());
        }
        return view.toString();
    }

    @Override
    public CursorPlacement cursorPlacement() {
        if (state != AppState.CHAT) {
            return CursorPlacement.afterView();
        }
        int inputStartColumn = 5;
        int contentWidth = inputContentWidth();
        String visibleInput = input.isEmpty() || streaming ? "" : input.toString().replace("\n", " -> ");
        int visibleInputWidth = displayWidth(stripAnsi(visibleInput));
        int column = inputStartColumn + Math.min(visibleInputWidth, contentWidth);
        return new CursorPlacement(3 + completion.lineCount(), column);
    }

    @Override
    public String dumpHistory() {
        if (chatMessages.isEmpty()) {
            return "";
        }
        StringBuilder dump = new StringBuilder();
        dump.append("BlueCode 会话记录").append(System.lineSeparator());
        for (ChatMessage message : chatMessages) {
            dump.append(switch (message.role()) {
                case USER -> "User";
                case ASSISTANT -> "Assistant";
                case TOOL -> "Tool";
                case SYSTEM -> "System";
            }).append(": ").append(message.content()).append(System.lineSeparator()).append(System.lineSeparator());
        }
        return dump.toString().stripTrailing();
    }

    private Command handleKey(KeyPressMessage key) {
        if ("ctrl+c".equals(key.key())) {
            if (state == AppState.APPROVING) {
                return cancelApproval();
            }
            if (state == AppState.CHAT && streaming && turnCancel != null) {
                turnCancel.cancel();
                return tick();
            }
            dispatchSessionEnd();
            return Command.quit();
        }
        if ("escape".equals(key.key()) && state == AppState.APPROVING) {
            return cancelApproval();
        }
        if ("escape".equals(key.key()) && state == AppState.CHAT && streaming && turnCancel != null) {
            turnCancel.cancel();
            return tick();
        }
        if (state == AppState.PROVIDER_SELECT) {
            return handleProviderKey(key);
        }
        if (state == AppState.RESUME) {
            return handleResumeKey(key);
        }
        if (state == AppState.APPROVING) {
            return handleApprovalKey(key);
        }
        if (streaming) {
            return Command.none();
        }
        Command completionCommand = handleCompletionKey(key);
        if (completionCommand != null) {
            return completionCommand;
        }
        return switch (key.key()) {
            case "enter" -> submitInput();
            case "shift+tab" -> cycleMode();
            case "alt+enter" -> {
                input.append('\n');
                syncCompletionFromInput();
                yield Command.none();
            }
            case "backspace" -> {
                if (!input.isEmpty()) {
                    input.deleteCharAt(input.length() - 1);
                }
                syncCompletionFromInput();
                yield Command.none();
            }
            case "text" -> {
                input.append(key.runes());
                syncCompletionFromInput();
                yield Command.none();
            }
            default -> Command.none();
        };
    }

    private Command handleProviderKey(KeyPressMessage key) {
        return switch (key.key()) {
            case "up" -> {
                selectedIndex = Math.floorMod(selectedIndex - 1, providers.size());
                yield Command.none();
            }
            case "down" -> {
                selectedIndex = Math.floorMod(selectedIndex + 1, providers.size());
                yield Command.none();
            }
            case "enter" -> {
                initializeProvider(selectedIndex);
                state = AppState.CHAT;
                yield Command.none();
            }
            default -> Command.none();
        };
    }

    private Command handleResumeKey(KeyPressMessage key) {
        return switch (key.key()) {
            case "escape" -> {
                state = AppState.CHAT;
                yield Command.none();
            }
            case "up" -> {
                if (!resumeFiltered.isEmpty()) {
                    resumeCursor = Math.floorMod(resumeCursor - 1, resumeFiltered.size());
                }
                yield Command.none();
            }
            case "down" -> {
                if (!resumeFiltered.isEmpty()) {
                    resumeCursor = Math.floorMod(resumeCursor + 1, resumeFiltered.size());
                }
                yield Command.none();
            }
            case "backspace" -> {
                if (!resumeQuery.isEmpty()) {
                    resumeQuery.deleteCharAt(resumeQuery.length() - 1);
                    filterResumeSessions();
                }
                yield Command.none();
            }
            case "enter" -> {
                resumeSelectedSession();
                yield Command.none();
            }
            case "text" -> {
                if (key.hasText()) {
                    resumeQuery.append(key.runes());
                    filterResumeSessions();
                }
                yield Command.none();
            }
            default -> Command.none();
        };
    }

    private Command submitInput() {
        String text = input.toString().strip();
        if (text.isEmpty()) {
            return Command.none();
        }
        Command slashCommand = dispatchSlash(text);
        if (slashCommand != null) {
            input.setLength(0);
            completion.hide();
            return slashCommand;
        }
        DispatchResult hookResult = dispatchHook(com.bluecode.hook.Event.USER_PROMPT_SUBMIT, Map.of("prompt", text));
        if (hookResult.blocked()) {
            error("[hook " + hookResult.blockingHookName() + "] " + hookResult.reason());
            return Command.none();
        }
        input.setLength(0);
        completion.hide();

        chatMessages.add(ChatMessage.user(text));
        conversation.addUserMessage(text);
        startTurn();
        return tick();
    }

    private void startTurn() {
        streamBuffer.setLength(0);
        streaming = true;
        streamStartedAt = System.currentTimeMillis();
        finishedElapsedMillis = 0;
        spinnerFrame = 0;
        iter = 0;
        currentTools.clear();
        turnCancel = new CancelToken();
        startLlmStream();
    }

    private void startLlmStream() {
        ensureAgent();
        agent.setToolContext(ToolContext.root().withCwd(effectiveCwd()));
        BlockingQueue<Event> queue = agent.run(conversation, mode, turnCancel);
        Thread.startVirtualThread(() -> {
            boolean done = false;
            while (!done) {
                try {
                    Event event = queue.take();
                    if (program != null) {
                        program.send(new AgentEventMessage(event));
                    }
                    done = event instanceof Event.Done || event instanceof Event.Failed;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    done = true;
                }
            }
        });
    }

    private Command handleAgentEvent(Event event) {
        switch (event) {
            case Event.Text text -> streamBuffer.append(text.delta());
            case Event.Tool tool -> handleToolEvent(tool.event());
            case CompactEvent compact -> {
                flushPreamble();
                chatMessages.add(ChatMessage.system(Commands.formatCompactNotice(compact)));
            }
            case Event.UsageReport usage -> {
                usageIn += usage.usage().input();
                usageOut += usage.usage().output();
            }
            case Event.Iter value -> iter = value.value();
            case Event.Notice notice -> {
                flushPreamble();
                chatMessages.add(ChatMessage.system(notice.message()));
            }
            case Event.Approval approval -> {
                pendingApproval = approval.request();
                approveCursor = 0;
                state = AppState.APPROVING;
            }
            case Event.Done ignored -> finishAssistant();
            case Event.Failed failed -> finishError(failed.message());
        }
        return Command.none();
    }

    private Command handleLeadMailEvent(LeadMailEvent event) {
        if (event == null) {
            return Command.none();
        }
        String text = event.displayText();
        if (idle()) {
            chatMessages.add(ChatMessage.user(text));
            conversation.addUserMessage(text);
            startTurn();
            return tick();
        }
        pushSystemMessage(text);
        return Command.none();
    }

    private Command handleApprovalKey(KeyPressMessage key) {
        if ("up".equals(key.key())) {
            approveCursor = Math.floorMod(approveCursor - 1, 3);
            return Command.none();
        }
        if ("down".equals(key.key())) {
            approveCursor = Math.floorMod(approveCursor + 1, 3);
            return Command.none();
        }
        if ("enter".equals(key.key())) {
            return sendApproval(outcomeForIndex(approveCursor));
        }
        if ("text".equals(key.key()) && key.hasText()) {
            char ch = key.runes()[0];
            return switch (ch) {
                case 'k' -> {
                    approveCursor = Math.floorMod(approveCursor - 1, 3);
                    yield Command.none();
                }
                case 'j' -> {
                    approveCursor = Math.floorMod(approveCursor + 1, 3);
                    yield Command.none();
                }
                case '1', 'y', 'Y' -> sendApproval(Outcome.ALLOW_ONCE);
                case '2' -> sendApproval(Outcome.ALLOW_FOREVER);
                case '3', 'n', 'N', 'd', 'D' -> sendApproval(Outcome.DENY_ONCE);
                default -> Command.none();
            };
        }
        return Command.none();
    }

    private Command cycleMode() {
        Mode[] values = Mode.values();
        mode = values[(mode.ordinal() + 1) % values.length];
        chatMessages.add(ChatMessage.system("权限模式已切换为 " + mode.displayName() + "。"));
        return Command.none();
    }

    private Command sendApproval(Outcome outcome) {
        if (pendingApproval != null) {
            pendingApproval.respond().offer(outcome);
        }
        pendingApproval = null;
        approveCursor = 0;
        state = AppState.CHAT;
        return tick();
    }

    private Command cancelApproval() {
        if (pendingApproval != null) {
            pendingApproval.respond().offer(Outcome.DENY_ONCE);
        }
        if (turnCancel != null) {
            turnCancel.cancel();
        }
        pendingApproval = null;
        approveCursor = 0;
        state = AppState.CHAT;
        return tick();
    }

    private Outcome outcomeForIndex(int index) {
        return switch (index) {
            case 1 -> Outcome.ALLOW_FOREVER;
            case 2 -> Outcome.DENY_ONCE;
            default -> Outcome.ALLOW_ONCE;
        };
    }

    private void handleToolEvent(com.bluecode.agent.ToolEvent event) {
        if (event.phase() == Phase.START) {
            flushPreamble();
            currentTools.add(new ToolDisplay(event.name(), event.args()));
            return;
        }
        ToolDisplay display = currentTools.isEmpty() ? new ToolDisplay(event.name(), event.args()) : currentTools.remove(0);
        String block = "* " + display.name() + "(" + display.args() + ")\n" + summarizeToolResult(event.result());
        chatMessages.add(ChatMessage.tool(block, event.isError()));
    }

    private void flushPreamble() {
        String preamble = streamBuffer.toString();
        if (!preamble.isBlank()) {
            chatMessages.add(ChatMessage.assistant(preamble, elapsedMillis()));
            streamBuffer.setLength(0);
        }
    }

    private String summarizeToolResult(String result) {
        if (result == null || result.isBlank()) {
            return "  - 无输出";
        }
        StringBuilder summary = new StringBuilder();
        String[] lines = result.strip().split("\\R", -1);
        int limit = Math.min(8, lines.length);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                summary.append('\n');
            }
            summary.append("  - ").append(lines[i]);
        }
        if (lines.length > limit) {
            summary.append('\n').append("  - [truncated]");
        }
        return summary.toString();
    }

    private void finishAssistant() {
        if (!streaming) {
            return;
        }
        finishedElapsedMillis = System.currentTimeMillis() - streamStartedAt;
        String answer = streamBuffer.toString();
        if (!answer.isBlank()) {
            chatMessages.add(ChatMessage.assistant(answer, finishedElapsedMillis));
        }
        finishTurn();
    }

    private void finishError(String message) {
        finishedElapsedMillis = System.currentTimeMillis() - streamStartedAt;
        chatMessages.add(ChatMessage.error(message));
        finishTurn();
    }

    private void finishTurn() {
        streaming = false;
        currentTools.clear();
        streamBuffer.setLength(0);
        iter = 0;
        turnCancel = null;
        pendingApproval = null;
        state = AppState.CHAT;
    }

    private void initializeProvider(int index) {
        selectedIndex = index;
        activeProvider = providers.get(index);
        runtime.contextWindow = activeProvider.effectiveContextWindow();
        client = LlmClient.create(activeProvider);
        if (sessionWriter != null) {
            sessionWriter.setModel(client.model());
        }
        if (memoryManager != null) {
            memoryManager.setProvider(client, client.model());
            memoryText = memoryManager.loadIndex();
        }
        agent = Agent.builder()
                .client(client)
                .registry(coordinatorMode ? registry.filtered(Coordinator.allowedTools()) : registry)
                .version(VERSION)
                .engine(engine)
                .runtime(runtime)
                .memoryManager(memoryManager)
                .instructionText(coordinatorMode ? instructionText + Coordinator.systemPromptSuffix() : instructionText)
                .memoryText(memoryText)
                .skillCatalog(skillCatalog)
                .hookEngine(hookEngine)
                .toolContext(ToolContext.root().withCwd(effectiveCwd()))
                .build();
        registry.get("Agent").ifPresent(tool -> {
            if (tool instanceof AgentTool agentTool) {
                agentTool.setParent(agent);
            }
        });
    }

    private void ensureAgent() {
        if (agent == null || client == null) {
            initializeProvider(Math.max(0, selectedIndex));
        }
    }

    public Agent mainAgent() {
        ensureAgent();
        return agent;
    }

    private DispatchResult dispatchHook(com.bluecode.hook.Event event, Map<String, ?> extras) {
        if (hookEngine == null) {
            return DispatchResult.empty();
        }
        DispatchResult result = hookEngine.dispatch(event, hookPayload(event, extras));
        runtime.appendReminders(result.injectedPrompts());
        return result;
    }

    private Payload hookPayload(com.bluecode.hook.Event event, Map<String, ?> extras) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("cwd", cwd.toString());
        data.put("event", event.wireName());
        data.put("mode", mode.name().toLowerCase());
        data.put("session_id", sessionId());
        if (extras != null) {
            data.putAll(extras);
        }
        return new Payload(data);
    }

    private void dispatchSessionStart() {
        if (sessionStartDispatched) {
            return;
        }
        sessionStartDispatched = true;
        sessionEndDispatched = false;
        dispatchHook(com.bluecode.hook.Event.SESSION_START, Map.of());
    }

    public void dispatchSessionEnd() {
        if (sessionEndDispatched) {
            return;
        }
        sessionEndDispatched = true;
        dispatchHook(com.bluecode.hook.Event.SESSION_END, Map.of());
    }

    private void dispatchSessionResume() {
        sessionStartDispatched = true;
        sessionEndDispatched = false;
        dispatchHook(com.bluecode.hook.Event.SESSION_RESUME, Map.of());
    }

    private void startTaskDoneConsumer() {
        if (taskManager == null) {
            return;
        }
        Thread.startVirtualThread(() -> {
            while (true) {
                try {
                    String id = taskManager.subscribeDone().take();
                    taskManager.get(id).ifPresent(task -> {
                        String notification = Tasks.buildTaskNotification(task);
                        conversation.addUserMessage(notification);
                        pushSystemMessage("后台任务完成: " + id + " (" + task.status().name().toLowerCase() + ")");
                        if (program != null) {
                            program.send(new TickMessage());
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    private void startLeadMailWatcher() {
        LeadMailWatcher.start(teamManager, runtime, event -> {
            if (program != null) {
                program.send(event);
            }
        });
    }

    void pushSystemMessage(String message) {
        chatMessages.add(ChatMessage.system(message));
    }

    void enterPlanMode() {
        mode = Mode.PLAN;
        pushSystemMessage("已进入计划模式：只允许 ReadFile / Glob / Grep。");
    }

    Command enterDoMode() {
        mode = Mode.DEFAULT;
        pushSystemMessage("已切换到执行模式。");
        conversation.addUserMessage(Reminder.EXECUTE_DIRECTIVE);
        startTurn();
        return tick();
    }

    void startManualCompact() {
        ensureAgent();
        List<Map<String, Object>> defs = currentToolDefinitions();
        Thread.startVirtualThread(() -> {
            Agent.ForceCompactResult result = agent.runForceCompact(conversation, defs);
            CompactEvent event = Commands.manualResultEvent(result.before(), result.after(), result.error());
            if (program != null) {
                program.send(new AgentEventMessage(event));
            } else {
                pushSystemMessage(Commands.formatCompactNotice(event));
            }
        });
    }

    void beginResume() {
        openResumeMenu();
    }

    private List<Map<String, Object>> currentToolDefinitions() {
        ensureAgent();
        return mode == Mode.PLAN ? agent.registry().readOnlyDefinitions() : agent.registry().definitions();
    }

    public Command dispatchSlash(String text) {
        Dispatch.Parsed parsed = Dispatch.parse(text);
        if (!parsed.isSlash()) {
            return null;
        }
        pendingUiCommand = Command.none();
        Optional<com.bluecode.command.Command> command = cmdRegistry.lookup(parsed.name());
        if (command.isEmpty()) {
            pushSystemMessage(unknownCommandMessage(parsed.name()));
            return takePendingUiCommand();
        }
        com.bluecode.command.Command slash = command.get();
        if ((slash.kind() == Kind.UI || slash.kind() == Kind.PROMPT) && !idle()) {
            error("请等待当前任务完成");
            return takePendingUiCommand();
        }
        try {
            currentCommandArguments = parsed.arguments();
            if (SkillCommands.isSkillCommand(slash)) {
                executeSkillCommand(parsed.name(), parsed.arguments());
            } else {
                slash.handler().handle(cancelled, this);
            }
        } catch (Exception e) {
            error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            currentCommandArguments = "";
        }
        return takePendingUiCommand();
    }

    private void registerSkillTools() {
        if (registry.get("LoadSkill").isEmpty()) {
            registry.register(new LoadSkillTool(skillCatalog, runtime.activeSkills));
        }
        if (registry.get("InstallSkill").isEmpty()) {
            registry.register(new InstallSkillTool(skillCatalog, cwd, this::refreshSkillCommands));
        }
    }

    private void refreshSkillCommands() {
        SkillCommands.removeSkillCommands(cmdRegistry);
        SkillCommands.registerSkillsAsCommands(cmdRegistry, skillCatalog);
    }

    private void executeSkillCommand(String name, String arguments) {
        skillCatalog.getFull(name).ifPresentOrElse(skill -> {
            SkillExecutor.assertAllowedToolsExist(skill, new com.bluecode.skill.SkillHost() {
                @Override
                public void activateSkill(String activeName, String body) {
                    runtime.activeSkills.activate(activeName, body);
                }

                @Override
                public void setToolFilter(java.util.function.Predicate<String> filter) {
                }

                @Override
                public Registry toolRegistry() {
                    return registry;
                }
            });
            String prompt = SkillExecutor.substituteArguments(skill.promptBody(), arguments);
            String label = "/" + skill.name();
            if (arguments != null && !arguments.isBlank()) {
                label += " " + arguments.strip();
            }
            chatMessages.add(ChatMessage.user(label));
            conversation.addUserMessage(prompt);
            pushSystemMessage("skill(" + skill.name() + ") Successfully loaded skill");
            startTurn();
            pendingUiCommand = tick();
        }, () -> error("unknown skill: " + name));
    }

    private Command handleCompletionKey(KeyPressMessage key) {
        if (!completion.active()) {
            return null;
        }
        return switch (key.key()) {
            case "up" -> {
                completion.moveUp();
                yield Command.none();
            }
            case "down" -> {
                completion.moveDown();
                yield Command.none();
            }
            case "escape" -> {
                completion.hide();
                yield Command.none();
            }
            case "tab" -> {
                com.bluecode.command.Command selected = completion.selected();
                if (selected == null) {
                    completion.hide();
                    yield Command.none();
                }
                input.setLength(0);
                input.append('/').append(selected.name());
                yield submitInput();
            }
            case "enter" -> {
                com.bluecode.command.Command selected = completion.selected();
                if (selected != null) {
                    input.setLength(0);
                    input.append('/').append(selected.name());
                }
                yield submitInput();
            }
            default -> null;
        };
    }

    private void syncCompletionFromInput() {
        completion.update(input.toString(), cmdRegistry);
    }

    private Command takePendingUiCommand() {
        Command command = pendingUiCommand == null ? Command.none() : pendingUiCommand;
        pendingUiCommand = Command.none();
        return command;
    }

    private String unknownCommandMessage(String name) {
        if (name == null || name.isBlank()) {
            return "未知命令: 输入 /help 查看可用命令";
        }
        return "未知命令: /" + name + "。输入 /help 查看可用命令";
    }

    @Override
    public void println(String msg) {
        pushSystemMessage(msg);
    }

    @Override
    public void error(String msg) {
        chatMessages.add(ChatMessage.error(msg));
    }

    @Override
    public Mode mode() {
        return mode;
    }

    @Override
    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DEFAULT : mode;
    }

    @Override
    public void injectAndSend(String displayLabel, String presetPrompt) {
        String label = displayLabel == null || displayLabel.isBlank() ? "/prompt" : displayLabel;
        String prompt = presetPrompt == null ? "" : presetPrompt;
        chatMessages.add(ChatMessage.user(label));
        conversation.addUserMessage(prompt);
        startTurn();
        pendingUiCommand = tick();
    }

    @Override
    public long usageIn() {
        return usageIn;
    }

    @Override
    public long usageOut() {
        return usageOut;
    }

    @Override
    public String modelName() {
        return activeProvider == null ? "" : activeProvider.getModel();
    }

    @Override
    public String cwd() {
        return effectiveCwd().toString();
    }

    @Override
    public String commandArguments() {
        return currentCommandArguments;
    }

    @Override
    public WorktreeAccessor worktreeAccessor() {
        return worktreeAccessor;
    }

    @Override
    public int toolCount() {
        return registry == null ? 0 : registry.count();
    }

    @Override
    public List<String> memoryFiles() {
        List<String> files = new ArrayList<>();
        files.addAll(memoryFiles(cwd.resolve(".bluecode").resolve("memory"), "project"));
        files.addAll(memoryFiles(Path.of(System.getProperty("user.home", "")).resolve(".bluecode").resolve("memory"), "user"));
        files.sort(String::compareTo);
        return List.copyOf(files);
    }

    @Override
    public List<HookRule> hookRules() {
        return hookEngine == null ? List.of() : hookEngine.rules();
    }

    @Override
    public List<String> hookSources() {
        return hookEngine == null ? List.of() : hookEngine.sources();
    }

    @Override
    public String sessionPath() {
        if (runtime == null || runtime.session == null || runtime.session.sessionDir() == null) {
            return "";
        }
        return runtime.session.sessionDir().toString();
    }

    @Override
    public String sessionId() {
        return runtime == null || runtime.session == null ? "" : runtime.session.sessionId();
    }

    @Override
    public void quit() {
        dispatchSessionEnd();
        cancelled.set(true);
        if (turnCancel != null) {
            turnCancel.cancel();
        }
        pendingUiCommand = Command.quit();
    }

    @Override
    public void forceCompact() {
        startManualCompact();
    }

    @Override
    public void openResumeMenu() {
        if (!idle()) {
            error("请等待当前任务完成");
            return;
        }
        try {
            resumeSessions.clear();
            resumeSessions.addAll(SessionList.list(sessionsDir));
        } catch (IOException e) {
            error("读取会话列表失败: " + e.getMessage());
            return;
        }
        if (resumeSessions.isEmpty()) {
            println("暂无可恢复会话。");
            return;
        }
        resumeQuery.setLength(0);
        filterResumeSessions();
        state = AppState.RESUME;
    }

    @Override
    public void clearAndNewSession() {
        dispatchSessionEnd();
        chatMessages.clear();
        currentTools.clear();
        streamBuffer.setLength(0);
        usageIn = 0;
        usageOut = 0;
        iter = 0;
        finishedElapsedMillis = 0;
        streaming = false;
        turnCancel = null;
        pendingApproval = null;
        state = AppState.CHAT;
        try {
            SessionContext nextSession = SessionContext.create(cwd);
            Writer nextWriter = Writer.create(nextSession.sessionDir());
            if (client != null) {
                nextWriter.setModel(client.model());
            }
            closeWriter();
            sessionWriter = nextWriter;
            conversation = new ConversationManager(sessionWriter::onAppend, sessionWriter::onReplace);
            runtime.resetForNewSession(nextSession);
            sessionStartDispatched = false;
            sessionEndDispatched = false;
            dispatchSessionStart();
            agent = null;
        } catch (IOException e) {
            error("创建新 session 失败: " + e.getMessage());
        }
    }

    @Override
    public boolean idle() {
        return state == AppState.CHAT && !streaming && pendingApproval == null;
    }

    @Override
    public List<String> listCatalogSkills() {
        return skillCatalog == null ? List.of() : skillCatalog.list().stream().map(SkillCatalog.Skill::name).toList();
    }

    @Override
    public List<String> listActiveSkills() {
        return runtime == null ? List.of() : runtime.activeSkills.names();
    }

    @Override
    public void clearActiveSkills() {
        if (runtime != null) {
            runtime.activeSkills.clear();
        }
    }

    @Override
    public void appendAssistantMessage(String text) {
        String content = text == null ? "" : text;
        conversation.addAssistantMessage(content);
        chatMessages.add(ChatMessage.assistant(content, 0));
    }

    CompletionMenu completion() {
        return completion;
    }

    List<ChatMessage> chatMessages() {
        return List.copyOf(chatMessages);
    }

    private List<String> memoryFiles(Path dir, String level) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".md"))
                    .map(path -> level + "/" + path.getFileName())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<String> listSessionDirs() {
        Path sessions = cwd.resolve(".bluecode").resolve("sessions");
        if (!Files.isDirectory(sessions)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(sessions)) {
            return stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), Comparator.reverseOrder()))
                    .limit(20)
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void filterResumeSessions() {
        String query = resumeQuery.toString().toLowerCase();
        resumeFiltered.clear();
        resumeFiltered.addAll(resumeSessions.stream()
                .filter(info -> query.isBlank()
                        || info.title().toLowerCase().contains(query)
                        || info.id().toLowerCase().contains(query)
                        || info.model().toLowerCase().contains(query))
                .toList());
        resumeCursor = resumeFiltered.isEmpty() ? 0 : Math.min(resumeCursor, resumeFiltered.size() - 1);
    }

    private void resumeSelectedSession() {
        if (resumeFiltered.isEmpty()) {
            return;
        }
        SessionInfo info = resumeFiltered.get(Math.max(0, Math.min(resumeCursor, resumeFiltered.size() - 1)));
        try {
            SessionLoader.LoadedSession loaded = SessionLoader.loadWithMetadata(info.dir());
            SessionContext restoredSession = SessionContext.open(cwd, info.id());
            Writer restoredWriter = writerFor(info);
            ConversationManager restoredConversation = ConversationManager.fromMessages(
                    loaded.messages(),
                    restoredWriter::onAppend,
                    restoredWriter::onReplace);
            dispatchSessionEnd();
            runtime.resetForNewSession(restoredSession);
            sessionEndDispatched = false;
            if (needsCompact(restoredConversation)) {
                ensureAgent();
                Agent.ForceCompactResult result = agent.runForceCompact(restoredConversation, currentToolDefinitions());
                if (result.error() != null) {
                    pushSystemMessage("恢复时自动压缩失败，已按原始会话继续: " + result.error().getMessage());
                }
            }
            appendTimeGapReminder(restoredConversation, loaded.lastTimestamp());
            swapWriter(restoredWriter);
            conversation = restoredConversation;
            rebuildChatMessages(conversation.getMessages());
            pushSystemMessage("已恢复会话 " + info.id() + "，共 " + conversation.size() + " 条消息");
            agent = null;
            state = AppState.CHAT;
            dispatchSessionResume();
        } catch (Exception e) {
            LOGGER.warning("恢复会话失败: " + e.getMessage());
            error("恢复会话失败: " + e.getMessage());
            state = AppState.CHAT;
        }
    }

    private Writer writerFor(SessionInfo info) throws IOException {
        if (sessionWriter != null && sessionWriter.file().toAbsolutePath().normalize()
                .equals(info.dir().resolve("conversation.jsonl").toAbsolutePath().normalize())) {
            return sessionWriter;
        }
        Writer writer = Writer.open(info.dir());
        if (client != null) {
            writer.setModel(client.model());
        }
        return writer;
    }

    private void swapWriter(Writer restoredWriter) {
        if (restoredWriter == sessionWriter) {
            return;
        }
        closeWriter();
        sessionWriter = restoredWriter;
    }

    public void closeWriter() {
        if (sessionWriter == null) {
            return;
        }
        try {
            sessionWriter.close();
        } catch (IOException e) {
            LOGGER.warning("关闭会话写入器失败: " + e.getMessage());
        }
    }

    private boolean needsCompact(ConversationManager restoredConversation) {
        if (runtime.contextWindow <= CompactConstants.SUMMARY_RESERVE + CompactConstants.AUTO_SAFETY_MARGIN) {
            return false;
        }
        long estimated = Token.estimateTokens(0, restoredConversation.getMessages(), 0);
        long threshold = runtime.contextWindow - CompactConstants.SUMMARY_RESERVE - CompactConstants.AUTO_SAFETY_MARGIN;
        return estimated >= threshold;
    }

    private void appendTimeGapReminder(ConversationManager restoredConversation, Instant lastTimestamp) {
        if (lastTimestamp == null || lastTimestamp.equals(Instant.EPOCH)) {
            return;
        }
        Duration gap = Duration.between(lastTimestamp, Instant.now());
        if (gap.compareTo(Duration.ofHours(6)) <= 0) {
            return;
        }
        restoredConversation.addUserMessage("[系统提示] 本会话已暂停 " + humanDuration(gap)
                + "。部分上下文可能已过时，如需最新信息请重新读取相关文件。");
    }

    private void rebuildChatMessages(List<Message> messages) {
        chatMessages.clear();
        for (Message message : messages) {
            switch (message.role()) {
                case USER -> chatMessages.add(ChatMessage.user(message.content()));
                case ASSISTANT -> {
                    if (!message.content().isBlank()) {
                        chatMessages.add(ChatMessage.assistant(message.content(), 0));
                    }
                    if (!message.toolCalls().isEmpty()) {
                        chatMessages.add(ChatMessage.tool("* tool_calls(" + message.toolCalls().size() + ")", false));
                    }
                }
                case TOOL -> chatMessages.add(ChatMessage.tool(summarizeToolResult(
                        message.toolResults().isEmpty() ? "" : message.toolResults().toString()), false));
            }
        }
    }

    private String renderResume() {
        StringBuilder builder = new StringBuilder();
        builder.append(Styles.TOOL.render("* 恢复会话")).append('\n');
        builder.append(Styles.MUTED.render("↑↓ 选择 · 输入字符过滤 · Backspace 删除 · Enter 恢复 · Esc 取消")).append('\n');
        if (!resumeQuery.isEmpty()) {
            builder.append(Styles.MUTED.render("搜索: " + resumeQuery)).append('\n');
        }
        if (resumeFiltered.isEmpty()) {
            builder.append("  无匹配会话").append('\n');
            return builder.toString();
        }
        int visible = Math.max(3, Math.min(resumeFiltered.size(), height - 14));
        int start = Math.max(0, Math.min(resumeCursor - visible / 2, resumeFiltered.size() - visible));
        for (int i = start; i < start + visible; i++) {
            SessionInfo info = resumeFiltered.get(i);
            String marker = i == resumeCursor ? "> " : "  ";
            String line = marker + info.title()
                    + "  ·  " + relativeTime(info.modifiedAt())
                    + "  ·  " + (info.model().isBlank() ? "model:-" : info.model())
                    + "  ·  " + formatSize(info.size());
            builder.append(i == resumeCursor ? Styles.STATUS.render(fitLine(line, Math.max(20, width - 2))) : line)
                    .append('\n');
        }
        return builder.toString();
    }

    private String relativeTime(Instant instant) {
        Duration duration = Duration.between(instant, Instant.now()).abs();
        long days = duration.toDays();
        if (days > 0) {
            return days + " day" + (days == 1 ? "" : "s") + " ago";
        }
        long hours = duration.toHours();
        if (hours > 0) {
            return hours + " hour" + (hours == 1 ? "" : "s") + " ago";
        }
        long minutes = Math.max(1, duration.toMinutes());
        return minutes + " min ago";
    }

    private String humanDuration(Duration duration) {
        long days = duration.toDays();
        if (days > 0) {
            return days + " 天";
        }
        long hours = duration.toHours();
        if (hours > 0) {
            return hours + " 小时";
        }
        return Math.max(1, duration.toMinutes()) + " 分钟";
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return "%.1f MB".formatted(bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024) {
            return "%.1f KB".formatted(bytes / 1024.0);
        }
        return bytes + " B";
    }

    private Command tick() {
        return Command.tick(Duration.ofMillis(250), ignored -> new TickMessage());
    }

    private String renderBanner() {
        return Styles.TITLE.render("BlueCode " + VERSION + "\n" + effectiveCwd());
    }

    private void initializeWorktreeState() {
        if (worktreeMgr == null) {
            return;
        }
        WorktreeSession session = worktreeMgr.currentSession();
        if (session != null && !session.worktreePath().isBlank()) {
            activeCwd = Path.of(session.worktreePath()).toAbsolutePath().normalize();
        }
        worktreeAccessor = new TuiWorktreeAccessor(worktreeMgr, path -> {
            activeCwd = path == null ? null : path.toAbsolutePath().normalize();
            if (agent != null) {
                agent.setToolContext(ToolContext.root().withCwd(effectiveCwd()));
            }
        });
    }

    private Path effectiveCwd() {
        return activeCwd == null ? cwd : activeCwd;
    }

    private String renderProviderSelect() {
        StringBuilder builder = new StringBuilder();
        builder.append(Styles.MUTED.render("选择本次会话使用的 provider，方向键移动，Enter 确认。")).append('\n');
        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig provider = providers.get(i);
            String marker = i == selectedIndex ? "> " : "  ";
            builder.append(marker)
                    .append(provider.getName())
                    .append("  ")
                    .append(Styles.MUTED.render("(" + provider.getModel() + ", " + provider.getProtocol() + ")"))
                    .append('\n');
        }
        builder.append(renderStatusBar());
        return builder.toString();
    }

    private String renderConversation() {
        StringBuilder builder = new StringBuilder();
        int maxMessages = Math.max(4, height - 12);
        int start = Math.max(0, chatMessages.size() - maxMessages);
        for (int i = start; i < chatMessages.size(); i++) {
            ChatMessage message = chatMessages.get(i);
            if (message.error()) {
                builder.append(Styles.ERROR.render("Error: ")).append(message.content()).append('\n');
                continue;
            }
            if (message.role() == ChatMessage.Role.USER) {
                builder.append(Styles.USER.render("User")).append('\n')
                        .append(indent(message.content())).append('\n');
            } else if (message.role() == ChatMessage.Role.ASSISTANT) {
                builder.append(Styles.ASSISTANT.render("Assistant"))
                        .append(Styles.MUTED.render("  " + formatElapsed(message.elapsedMillis())))
                        .append('\n')
                        .append(markdownRenderer.render(message.content(), width)).append('\n');
            } else if (message.role() == ChatMessage.Role.TOOL) {
                builder.append(renderToolMessage(message)).append('\n');
            } else if (message.role() == ChatMessage.Role.SYSTEM) {
                builder.append(Styles.MUTED.render("System  " + message.content())).append('\n');
            }
        }
        return builder.toString();
    }

    private String renderStreaming() {
        long seconds = Math.max(0, (System.currentTimeMillis() - streamStartedAt) / 1000);
        StringBuilder builder = new StringBuilder();
        if (currentTools.isEmpty()) {
            String round = iter > 0 ? " · 第 " + iter + " 轮" : "";
            builder.append(Styles.MUTED.render(SpinnerVerbs.frame(spinnerFrame) + "... (" + seconds + "s" + round + ")")).append('\n');
        } else {
            for (ToolDisplay tool : currentTools) {
                builder.append(Styles.MUTED.render("* " + tool.name() + "(" + tool.args() + ")  Running... (" + seconds + "s)"))
                        .append('\n');
            }
        }
        if (!streamBuffer.isEmpty()) {
            builder.append(Styles.ASSISTANT.render("Assistant")).append('\n')
                    .append(indent(streamBuffer.toString())).append('\n');
        }
        return builder.toString();
    }

    private String renderApproval() {
        String[] labels = {
                "1. 允许本次",
                "2. 永久允许（写入本地配置）",
                "3. 拒绝本次"
        };
        StringBuilder builder = new StringBuilder();
        builder.append(Styles.TOOL.render("* 待批准工具调用")).append('\n');
        builder.append("  ").append(pendingApproval.name()).append("(").append(pendingApproval.args()).append(")").append('\n');
        builder.append(Styles.MUTED.render("  " + pendingApproval.reason())).append('\n');
        for (int i = 0; i < labels.length; i++) {
            String marker = i == approveCursor ? "> " : "  ";
            String line = marker + labels[i];
            builder.append(i == approveCursor ? Styles.STATUS.render(line) : line).append('\n');
        }
        builder.append(Styles.MUTED.render("↑↓ 选择 · Enter 确认 · Esc 取消")).append('\n');
        return builder.toString();
    }

    private String renderInputBox() {
        int boxWidth = Math.max(30, width - 2);
        String border = "-".repeat(Math.max(1, boxWidth - 2));
        String content = input.isEmpty() ? Styles.MUTED.render("Send a message...") : input.toString();
        if (state == AppState.APPROVING) {
            content = Styles.MUTED.render("等待权限选择...");
        } else if (state == AppState.RESUME) {
            content = Styles.MUTED.render("选择要恢复的会话...");
        } else if (streaming) {
            content = Styles.MUTED.render("等待当前回复完成...");
        }
        return "+" + border + "+\n"
                + "| > " + fitLine(content, inputContentWidth()) + " |\n"
                + "+" + border + "+\n";
    }

    private String renderStatusBar() {
        String left = modeLabel();
        String right = activeProvider == null ? "model: -" : activeProvider.getModel();
        if (!currentTools.isEmpty()) {
            right += "  tools:" + currentTools.size();
        }
        right += "  ↑" + formatTokens(usageIn) + " ↓" + formatTokens(usageOut) + " tok";
        int spaces = Math.max(1, width - stripAnsi(left).length() - stripAnsi(right).length());
        return Styles.STATUS.render(left + " ".repeat(spaces) + right);
    }

    private String modeLabel() {
        String label = switch (mode) {
            case DEFAULT -> "DEFAULT";
            case ACCEPT_EDITS -> "ACCEPT EDITS";
            case PLAN -> "PLAN";
            case BYPASS -> "BYPASS";
        };
        return coordinatorMode ? label + " [COORDINATOR]" : label;
    }

    private String renderToolMessage(ChatMessage message) {
        String[] lines = message.content().split("\\R", -1);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            if (i == 0) {
                builder.append((message.error() ? Styles.ERROR : Styles.TOOL).render(lines[i]));
            } else {
                builder.append((message.error() ? Styles.ERROR : Styles.TOOL_RESULT).render(lines[i]));
            }
        }
        return builder.toString();
    }

    private String indent(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            builder.append("  ").append(line).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    private String fitLine(String text, int max) {
        String plain = stripAnsi(text).replace("\n", " -> ");
        int visibleWidth = displayWidth(plain);
        if (visibleWidth <= max) {
            return text + " ".repeat(Math.max(0, max - visibleWidth));
        }
        return truncateToDisplayWidth(plain, Math.max(1, max - 3)) + "...";
    }

    private String stripAnsi(String text) {
        return text == null ? "" : text.replaceAll("\\u001B\\[[;\\d]*m", "");
    }

    private int inputContentWidth() {
        int boxWidth = Math.max(30, width - 2);
        return Math.max(1, boxWidth - 6);
    }

    private int displayWidth(String text) {
        int displayWidth = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            displayWidth += Math.max(0, WCWidth.wcwidth(codePoint));
            i += Character.charCount(codePoint);
        }
        return displayWidth;
    }

    private String truncateToDisplayWidth(String text, int maxWidth) {
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charWidth = Math.max(0, WCWidth.wcwidth(codePoint));
            if (used + charWidth > maxWidth) {
                break;
            }
            result.appendCodePoint(codePoint);
            used += charWidth;
            i += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private String formatElapsed(long millis) {
        if (millis <= 0) {
            return "";
        }
        return "(" + Math.max(1, millis / 1000) + "s)";
    }

    private String formatTokens(long tokens) {
        if (tokens >= 1_000_000) {
            return "%.1fm".formatted(tokens / 1_000_000.0);
        }
        if (tokens >= 1_000) {
            return "%.1fk".formatted(tokens / 1_000.0);
        }
        return Long.toString(tokens);
    }

    private long elapsedMillis() {
        return Math.max(0, System.currentTimeMillis() - streamStartedAt);
    }

    private record ToolDisplay(String name, String args) {
    }
}
