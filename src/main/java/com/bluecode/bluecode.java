package com.bluecode;

import com.bluecode.agent.Agent;
import com.bluecode.agent.AgentTool;
import com.bluecode.agent.CancelToken;
import com.bluecode.agent.CompactEvent;
import com.bluecode.agent.Event;
import com.bluecode.agent.Phase;
import com.bluecode.agent.SessionRuntime;
import com.bluecode.agent.TeamHook;
import com.bluecode.cli.TeamMemberRunner;
import com.bluecode.config.AppConfig;
import com.bluecode.config.ConfigException;
import com.bluecode.config.ConfigLoader;
import com.bluecode.config.ProviderConfig;
import com.bluecode.conversation.ConversationManager;
import com.bluecode.coordinator.Coordinator;
import com.bluecode.hook.HookEngine;
import com.bluecode.hook.HookLoader;
import com.bluecode.hook.Payload;
import com.bluecode.instructions.Loader;
import com.bluecode.llm.LlmClient;
import com.bluecode.memory.Manager;
import com.bluecode.permission.Mode;
import com.bluecode.permission.Outcome;
import com.bluecode.permission.PermissionEngine;
import com.bluecode.mcp.McpManager;
import com.bluecode.session.SessionCleaner;
import com.bluecode.session.Writer;
import com.bluecode.subagent.Catalog;
import com.bluecode.team.SpawnTeammate;
import com.bluecode.team.TeamManager;
import com.bluecode.task.SendMessageTool;
import com.bluecode.task.TaskGetTool;
import com.bluecode.task.TaskListTool;
import com.bluecode.task.TaskStopTool;
import com.bluecode.teams.AgentNameRegistry;
import com.bluecode.teams.TeamCreateTool;
import com.bluecode.teams.TeamDeleteTool;
import com.bluecode.teams.TaskCreateTool;
import com.bluecode.teams.TaskUpdateTool;
import com.bluecode.tool.Registry;
import com.bluecode.tool.Tool;
import com.bluecode.tui.bluecodeModel;
import com.bluecode.tui.tea.Program;
import com.bluecode.worktree.WorktreeManager;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

public final class bluecode {
    private static final String CONFIG_PATH = ".bluecode/config.yaml";
    private static final String VERSION = "0.2.0";

    private bluecode() {
    }

    public static void main(String[] args) {
        try {
            AppConfig config = ConfigLoader.load(CONFIG_PATH);
            String prompt = promptArg(args);
            if (prompt != null) {
                runOnce(config, prompt);
                return;
            }

            Path root = Path.of("").toAbsolutePath();
            try (McpRuntime runtime = createMcpRuntime(root)) {
                String instructionText = loadInstructions(root);
                Manager memoryManager = createMemoryManager(root);
                String memoryText = memoryManager.loadIndex();
                PermissionEngine engine = PermissionEngine.create(root);
                HookEngine hookEngine = HookLoader.load(root);
                Catalog subAgentCatalog = Catalog.load(root);
                WorktreeManager worktreeMgr = createWorktreeManager(root);
                com.bluecode.task.Manager taskManager = new com.bluecode.task.Manager();
                AgentNameRegistry nameRegistry = new AgentNameRegistry();
                taskManager.setNameRegistry(nameRegistry);
                TeamManager teamManager = createTeamManager(root, worktreeMgr, taskManager, nameRegistry);
                taskManager.onTaskDone(teamManager::handleTaskDone);
                TeamHook teamHook = new SpawnTeammate(teamManager, worktreeMgr, taskManager, nameRegistry, root, subAgentCatalog);
                if (TeamMemberRunner.isTeamMember(args)) {
                    TeamMemberRunner.run(new TeamMemberRunner.Context(teamManager), TeamMemberRunner.parse(args));
                    return;
                }
                AgentTool agentTool = registerSubAgentTools(
                        runtime.registry(),
                        subAgentCatalog,
                        taskManager,
                        config.effectiveEnableSubAgentBackground(),
                        worktreeMgr,
                        teamManager,
                        teamHook);
                int contextWindow = config.getProviders().size() == 1
                        ? config.getProviders().getFirst().effectiveContextWindow()
                        : 200000;
                SessionRuntime sessionRuntime = SessionRuntime.create(root, contextWindow);
                Thread.startVirtualThread(() -> SessionCleaner.cleanExpired(
                        root.resolve(".bluecode").resolve("sessions"),
                        Duration.ofDays(30)));
                try (Writer writer = Writer.create(sessionRuntime.session.sessionDir())) {
                    bluecodeModel model = new bluecodeModel(
                            config.getProviders(),
                            runtime.registry(),
                            engine,
                            sessionRuntime,
                            writer,
                            memoryManager,
                            instructionText,
                            memoryText,
                            root,
                            taskManager,
                            subAgentCatalog,
                            hookEngine,
                            worktreeMgr,
                            teamManager,
                            Coordinator.isEnabled(config));
                    agentTool.setParent(model.mainAgent());
                    Program program = new Program(model);
                    model.setProgram(program);
                    try {
                        program.run();
                    } finally {
                        model.dispatchSessionEnd();
                        model.closeWriter();
                    }
                }
            }
        } catch (ConfigException e) {
            System.err.println("配置错误: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String promptArg(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("-p".equals(args[i]) && i + 1 < args.length) {
                return args[i + 1];
            }
            if (args[i].startsWith("--prompt=")) {
                return args[i].substring("--prompt=".length());
            }
        }
        return null;
    }

    private static void runOnce(AppConfig config, String prompt) throws Exception {
        ProviderConfig provider = config.getProviders().getFirst();
        LlmClient client = LlmClient.create(provider);
        Path root = Path.of("").toAbsolutePath();
        try (McpRuntime runtime = createMcpRuntime(root)) {
            String instructionText = loadInstructions(root);
            Manager memoryManager = createMemoryManager(root);
            String memoryText = memoryManager.loadIndex();
            memoryManager.setProvider(client, client.model());
            PermissionEngine engine = PermissionEngine.create(root);
            HookEngine hookEngine = HookLoader.load(root);
            Catalog subAgentCatalog = Catalog.load(root);
            WorktreeManager worktreeMgr = createWorktreeManager(root);
            com.bluecode.task.Manager taskManager = new com.bluecode.task.Manager();
            AgentNameRegistry nameRegistry = new AgentNameRegistry();
            taskManager.setNameRegistry(nameRegistry);
            TeamManager teamManager = createTeamManager(root, worktreeMgr, taskManager, nameRegistry);
            taskManager.onTaskDone(teamManager::handleTaskDone);
            TeamHook teamHook = new SpawnTeammate(teamManager, worktreeMgr, taskManager, nameRegistry, root, subAgentCatalog);
            AgentTool agentTool = registerSubAgentTools(runtime.registry(), subAgentCatalog, taskManager,
                    config.effectiveEnableSubAgentBackground(), worktreeMgr, teamManager, teamHook);
            SessionRuntime sessionRuntime = SessionRuntime.create(root, provider.effectiveContextWindow());
            sessionRuntime.hookEngine = hookEngine;
            try (Writer writer = Writer.create(sessionRuntime.session.sessionDir())) {
                writer.setModel(client.model());
                ConversationManager conversation = new ConversationManager(writer::onAppend, writer::onReplace);
                dispatchSessionHook(hookEngine, sessionRuntime, com.bluecode.hook.Event.SESSION_START, root);
                conversation.addUserMessage(prompt);
                Agent mainAgent = Agent.builder()
                        .client(client)
                        .registry(runtime.registry())
                        .version(VERSION)
                        .engine(engine)
                        .runtime(sessionRuntime)
                        .memoryManager(memoryManager)
                        .instructionText(instructionText)
                        .memoryText(memoryText)
                        .hookEngine(hookEngine)
                        .build();
                agentTool.setParent(mainAgent);
                BlockingQueue<Event> queue = mainAgent.run(conversation, Mode.BYPASS, new CancelToken());

                boolean done = false;
                while (!done) {
                    Event event = queue.take();
                    switch (event) {
                        case Event.Text text -> {
                            System.out.print(text.delta());
                            System.out.flush();
                        }
                        case Event.Tool tool -> printToolEvent(tool.event());
                        case Event.UsageReport ignored -> {
                        }
                        case Event.Iter ignored -> {
                        }
                        case Event.Notice notice -> System.err.println(notice.message());
                        case Event.Approval approval -> approval.request().respond().offer(Outcome.DENY_ONCE);
                        case CompactEvent compact -> System.err.println(compact.phase());
                        case Event.Done ignored -> {
                            System.out.println();
                            done = true;
                        }
                        case Event.Failed failed -> {
                            System.err.println("错误: " + failed.message());
                            done = true;
                        }
                    }
                }
                dispatchSessionHook(hookEngine, sessionRuntime, com.bluecode.hook.Event.SESSION_END, root);
            }
        }
    }

    private static String sessionIdSafe(SessionRuntime sessionRuntime) {
        if (sessionRuntime == null || sessionRuntime.session == null) {
            return "";
        }
        String id = sessionRuntime.session.sessionId();
        return id == null ? "" : id;
    }

    private static void dispatchSessionHook(
            HookEngine hookEngine,
            SessionRuntime sessionRuntime,
            com.bluecode.hook.Event event,
            Path root) {
        if (hookEngine == null || sessionRuntime == null || event == null) {
            return;
        }
        var result = hookEngine.dispatch(event, new Payload(Map.of(
                "cwd", root == null ? Path.of("").toAbsolutePath().normalize().toString() : root.toString(),
                "event", event.wireName(),
                "mode", "bypass",
                "session_id", sessionIdSafe(sessionRuntime))));
        sessionRuntime.appendReminders(result.injectedPrompts());
    }

    private static String loadInstructions(Path root) {
        try {
            return new Loader(root).load();
        } catch (Exception e) {
            System.err.println("[instructions] warn: " + e.getMessage());
            return "";
        }
    }

    private static Manager createMemoryManager(Path root) {
        Path projectMemory = root.resolve(".bluecode").resolve("memory");
        Path userMemory = Path.of(System.getProperty("user.home")).resolve(".bluecode").resolve("memory");
        return new Manager(projectMemory, userMemory, null, "");
    }

    private static McpRuntime createMcpRuntime(Path root) {
        Registry registry = Registry.createDefault();
        McpManager manager = McpManager.start(com.bluecode.mcp.ConfigLoader.loadConfig(root), VERSION);
        Thread shutdownHook = new Thread(manager::close, "mcp-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        for (Tool tool : manager.tools()) {
            try {
                registry.register(tool);
            } catch (IllegalArgumentException e) {
                System.err.println("[mcp] warn: skip registry tool " + tool.name() + ": " + e.getMessage());
            }
        }
        return new McpRuntime(registry, manager, shutdownHook);
    }

    private static AgentTool registerSubAgentTools(
            Registry registry,
            Catalog catalog,
            com.bluecode.task.Manager taskManager,
            boolean backgroundEnabled,
            WorktreeManager worktreeMgr,
            TeamManager teamManager,
            TeamHook teamHook) {
        registry.register(new TaskListTool(taskManager, teamManager));
        registry.register(new TaskGetTool(taskManager, teamManager));
        registry.register(new TaskStopTool(taskManager));
        registry.register(new SendMessageTool(taskManager, teamManager));
        if (teamManager != null) {
            registry.register(new TeamCreateTool(teamManager));
            registry.register(new TeamDeleteTool(teamManager));
            registry.register(new TaskCreateTool(teamManager));
            registry.register(new TaskUpdateTool(teamManager));
        }
        AgentTool agentTool = new AgentTool(catalog, taskManager, null, backgroundEnabled, worktreeMgr, teamHook);
        registry.register(agentTool);
        return agentTool;
    }

    private static TeamManager createTeamManager(
            Path root,
            WorktreeManager worktreeMgr,
            com.bluecode.task.Manager taskManager,
            AgentNameRegistry nameRegistry) throws IOException {
        return new TeamManager(
                Path.of(System.getProperty("user.home")),
                root,
                worktreeMgr,
                taskManager,
                nameRegistry);
    }

    private static WorktreeManager createWorktreeManager(Path root) {
        try {
            WorktreeManager manager = new WorktreeManager(root);
            Thread.startVirtualThread(() -> manager.sweepStale(Instant.now().minus(24, ChronoUnit.HOURS)));
            warnMissingWorktreeGitignore(root);
            return manager;
        } catch (IOException e) {
            System.err.println("Worktree 管理器降级: " + e.getMessage());
            return null;
        }
    }

    private static void warnMissingWorktreeGitignore(Path root) {
        try {
            Path ignore = root.resolve(".gitignore");
            String text = java.nio.file.Files.exists(ignore)
                    ? java.nio.file.Files.readString(ignore)
                    : "";
            if (!text.contains(".bluecode/worktrees/") || !text.contains(".bluecode/worktree_session.json")) {
                System.err.println("worktree: 建议在 .gitignore 中加入 .bluecode/worktrees/ 和 .bluecode/worktree_session.json");
            }
        } catch (Exception ignored) {
            // .gitignore 检查只提示,不影响启动。
        }
    }

    private record McpRuntime(Registry registry, McpManager manager, Thread shutdownHook) implements AutoCloseable {
        @Override
        public void close() {
            manager.close();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM 正在关闭时 shutdown hook 已开始执行，忽略即可。
            }
        }
    }

    private static void printToolEvent(com.bluecode.agent.ToolEvent event) {
        if (event.phase() == Phase.START) {
            System.out.println();
            System.out.println("* " + event.name() + "(" + event.args() + ")");
            return;
        }
        String[] lines = event.result() == null ? new String[0] : event.result().strip().split("\\R", -1);
        int limit = Math.min(6, lines.length);
        for (int i = 0; i < limit; i++) {
            System.out.println("  - " + lines[i]);
        }
        if (lines.length > limit) {
            System.out.println("  - [truncated]");
        }
    }
}
