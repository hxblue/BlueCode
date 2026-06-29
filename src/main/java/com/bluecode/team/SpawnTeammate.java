package com.bluecode.team;

import com.bluecode.agent.Agent;
import com.bluecode.agent.ApprovalUpgrader;
import com.bluecode.agent.CancelToken;
import com.bluecode.agent.Fork;
import com.bluecode.agent.TeamHook;
import com.bluecode.agent.TeamSpawnRequest;
import com.bluecode.agent.TeammateContext;
import com.bluecode.conversation.ConversationManager;
import com.bluecode.permission.Mode;
import com.bluecode.permission.Outcome;
import com.bluecode.subagent.Catalog;
import com.bluecode.subagent.Definition;
import com.bluecode.team.exceptions.TeamNotFoundException;
import com.bluecode.teams.AgentNameRegistry;
import com.bluecode.teams.Backend;
import com.bluecode.teams.BackendFactory;
import com.bluecode.teams.Mailbox;
import com.bluecode.teams.Message;
import com.bluecode.teams.MessageType;
import com.bluecode.teams.ReadUnreadResult;
import com.bluecode.teams.SpawnRequest;
import com.bluecode.teams.SpawnResult;
import com.bluecode.tool.Filter;
import com.bluecode.tool.Registry;
import com.bluecode.tool.ToolContext;
import com.bluecode.worktree.Worktree;
import com.bluecode.worktree.WorktreeManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class SpawnTeammate implements TeamHook {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TeamManager teamManager;
    private final WorktreeManager worktreeManager;
    private final com.bluecode.task.Manager taskManager;
    private final AgentNameRegistry registry;
    private final Path projectRoot;
    private final Catalog catalog;

    public SpawnTeammate(
            TeamManager teamManager,
            WorktreeManager worktreeManager,
            com.bluecode.task.Manager taskManager,
            AgentNameRegistry registry,
            Path projectRoot) {
        this(teamManager, worktreeManager, taskManager, registry, projectRoot, null);
    }

    public SpawnTeammate(
            TeamManager teamManager,
            WorktreeManager worktreeManager,
            com.bluecode.task.Manager taskManager,
            AgentNameRegistry registry,
            Path projectRoot,
            Catalog catalog) {
        this.teamManager = teamManager;
        this.worktreeManager = worktreeManager;
        this.taskManager = taskManager;
        this.registry = registry == null ? new AgentNameRegistry() : registry;
        this.projectRoot = (projectRoot == null ? Path.of("").toAbsolutePath() : projectRoot).toAbsolutePath().normalize();
        this.catalog = catalog;
    }

    @Override
    public String spawnTeammate(TeamSpawnRequest request) throws IOException {
        Team team = teamManager.get(request.teamName()).orElseThrow(() -> new TeamNotFoundException(request.teamName()));
        Catalog effectiveCatalog = catalog == null ? Catalog.load(projectRoot) : catalog;
        boolean fork = request.subagentType().isBlank();
        Definition definition = fork
                ? effectiveCatalog.forkDefinition()
                : effectiveCatalog.resolve(request.subagentType())
                .orElseThrow(() -> new IllegalArgumentException("未知 subagent_type: " + request.subagentType()));
        String memberName = memberName(request.memberName());
        String agentId = "agent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        WorktreeInfo worktree = createWorktree(team, memberName);
        Path sessionDir = projectRoot.resolve(".bluecode").resolve("sessions").resolve(agentId).toAbsolutePath().normalize();
        Files.createDirectories(sessionDir);

        ConversationManager childConversation = fork
                ? forkConversation(request.parentConversation(), request.prompt())
                : new ConversationManager();
        ToolContext toolContext = request.toolContext().withCwd(worktree.path());
        TeammateContext teammateContext = teammateContext(team, memberName, agentId, team.backend());
        Agent child = buildChildAgent(request, definition, childConversation, toolContext, teammateContext);

        Backend backend = BackendFactory.create(team.backend(), teamManager.backendDeps());
        if (team.backend() != BackendType.IN_PROCESS) {
            new Mailbox(team.mailboxDir()).write(agentId, new Message(
                    team.leadAgentId(),
                    agentId,
                    MessageType.TEXT,
                    request.description(),
                    request.prompt(),
                    Map.of(),
                    0,
                    false));
        }
        SpawnResult result = backend.spawn(new SpawnRequest(
                team.name(),
                memberName,
                agentId,
                worktree.path().toString(),
                sessionDir.toString(),
                definition.isFork() ? "" : definition.name(),
                effectiveModel(request.model(), definition),
                request.prompt(),
                definition.permissionMode() == Mode.PLAN,
                child,
                childConversation,
                taskManager));
        String effectiveAgentId = result.agentId().isBlank() ? agentId : result.agentId();
        registry.register(memberName, effectiveAgentId);
        team.addMember(new TeammateInfo(
                memberName,
                effectiveAgentId,
                definition.isFork() ? "" : definition.name(),
                effectiveModel(request.model(), definition),
                worktree.path().toString(),
                worktree.branch(),
                team.backend(),
                result.paneId(),
                true,
                definition.permissionMode() == Mode.PLAN,
                sessionDir.toString()));
        return json(Map.of(
                "teamName", team.name(),
                "memberName", memberName,
                "agentId", effectiveAgentId,
                "backend", team.backend().wireValue(),
                "worktree", worktree.path().toString(),
                "paneId", result.paneId()));
    }

    private Agent buildChildAgent(
            TeamSpawnRequest request,
            Definition definition,
            ConversationManager childConversation,
            ToolContext toolContext,
            TeammateContext teammateContext) {
        Agent parent = request.parentAgent();
        List<String> allowedTools = Filter.applyAgentToolFilter(new Filter.FilterParams(
                parent.registry().names(),
                definition.source().ordinal() + 1,
                request.runInBackground() || definition.background(),
                definition.tools(),
                definition.disallowedTools(),
                true));
        Registry childRegistry = parent.registry().filtered(allowedTools);
        String teamPrompt = definition.systemPrompt()
                + "\n\n<team-context>\n"
                + "你是 Team " + request.teamName() + " 的队员。通过 TaskCreate/TaskList/TaskUpdate/SendMessage 协作。"
                + "\n</team-context>";
        return Agent.builder()
                .client(parent.client())
                .registry(childRegistry)
                .version(parent.version())
                .engine(parent.engine())
                .runtime(com.bluecode.agent.SessionRuntime.empty(parent.contextWindow()))
                .systemPrompt(teamPrompt)
                .maxTurns(definition.maxTurns())
                .permissionMode(definition.permissionMode())
                .dontAsk(true)
                .approvalUpgrader((CancelToken cancel, com.bluecode.agent.ApprovalRequest approval) -> Optional.of(Outcome.DENY_ONCE))
                .subAgent(true)
                .toolContext(toolContext)
                .teammateContext(teammateContext)
                .build();
    }

    private ConversationManager forkConversation(ConversationManager parentConversation, String prompt) {
        if (parentConversation == null) {
            return new ConversationManager();
        }
        return ConversationManager.fromMessages(
                Fork.buildForkedMessages(parentConversation.getMessages(), prompt),
                null,
                null);
    }

    private TeammateContext teammateContext(Team team, String memberName, String agentId, BackendType backendType) {
        return new TeammateContext(
                team.name(),
                memberName,
                agentId,
                backendType,
                () -> readUnreadView(team, agentId),
                indices -> {
                    try {
                        new Mailbox(team.mailboxDir()).markRead(agentId, indices);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    private TeammateContext.ReadUnreadView readUnreadView(Team team, String agentId) {
        try {
            ReadUnreadResult unread = new Mailbox(team.mailboxDir()).readUnread(agentId);
            List<TeammateContext.IncomingMessage> messages = unread.messages().stream()
                    .map(message -> new TeammateContext.IncomingMessage(
                            message.from(),
                            message.type().wireValue(),
                            message.timestamp(),
                            message.summary(),
                            message.content(),
                            message.payload()))
                    .toList();
            return new TeammateContext.ReadUnreadView(unread.indices(), messages);
        } catch (IOException e) {
            return new TeammateContext.ReadUnreadView(List.of(), List.of());
        }
    }

    private WorktreeInfo createWorktree(Team team, String memberName) throws IOException {
        if (worktreeManager == null) {
            return new WorktreeInfo(projectRoot, "");
        }
        String name = "team-" + team.sanitizedName() + "/" + memberName;
        Worktree worktree = worktreeManager.create(name, "HEAD", false);
        return new WorktreeInfo(worktree.path(), worktree.branch());
    }

    private static String memberName(String requested) {
        String sanitized = Persistence.sanitize(requested);
        if (!sanitized.isBlank()) {
            return sanitized;
        }
        return "agent-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    private static String effectiveModel(String requested, Definition definition) {
        if (requested != null && !requested.isBlank() && !"inherit".equals(requested)) {
            return requested;
        }
        return definition.model();
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"json encode failed\"}";
        }
    }

    private record WorktreeInfo(Path path, String branch) {
    }
}
