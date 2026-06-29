package com.bluecode.agent;

import com.bluecode.conversation.ConversationManager;
import com.bluecode.permission.Mode;
import com.bluecode.permission.Outcome;
import com.bluecode.subagent.Catalog;
import com.bluecode.subagent.Definition;
import com.bluecode.team.BackendType;
import com.bluecode.team.exceptions.InProcessTeammateNoSpawnException;
import com.bluecode.tool.Filter;
import com.bluecode.tool.Registry;
import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;
import com.bluecode.tool.ToolContext;
import com.bluecode.worktree.AutoCleanupReport;
import com.bluecode.worktree.WorktreeManager;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;

public final class AgentTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Catalog catalog;
    private final com.bluecode.task.Manager taskManager;
    private final boolean backgroundEnabled;
    private final WorktreeManager worktreeManager;
    private final TeamHook teamHook;
    private volatile Agent parentAgent;

    public AgentTool(Catalog catalog, com.bluecode.task.Manager taskManager, Agent parentAgent,
                     boolean backgroundEnabled) {
        this(catalog, taskManager, parentAgent, backgroundEnabled, null);
    }

    public AgentTool(Catalog catalog, com.bluecode.task.Manager taskManager, Agent parentAgent,
                     boolean backgroundEnabled, WorktreeManager worktreeManager) {
        this(catalog, taskManager, parentAgent, backgroundEnabled, worktreeManager, null);
    }

    public AgentTool(Catalog catalog, com.bluecode.task.Manager taskManager, Agent parentAgent,
                     boolean backgroundEnabled, WorktreeManager worktreeManager, TeamHook teamHook) {
        this.catalog = catalog;
        this.taskManager = taskManager;
        this.parentAgent = parentAgent;
        this.backgroundEnabled = backgroundEnabled;
        this.worktreeManager = worktreeManager;
        this.teamHook = teamHook;
    }

    public void setParent(Agent parentAgent) {
        this.parentAgent = parentAgent;
    }

    @Override
    public String name() {
        return "Agent";
    }

    @Override
    public String description() {
        StringBuilder builder = new StringBuilder("启动一个独立上下文的子 Agent。");
        if (catalog != null && !catalog.list().isEmpty()) {
            builder.append(" 可用 subagent_type: ");
            builder.append(String.join(", ", catalog.list().stream().map(Definition::name).toList()));
            builder.append("。留空时走 Fork 路径。");
        }
        return builder.toString();
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("prompt", Map.of("type", "string", "description", "交给子 Agent 的任务指令"));
        properties.put("description", Map.of("type", "string", "description", "一句话任务描述"));
        properties.put("subagent_type", Map.of("type", "string", "description", "角色名;留空表示 Fork"));
        properties.put("model", Map.of("type", "string", "description", "模型覆盖: haiku/sonnet/opus/inherit"));
        properties.put("run_in_background", Map.of("type", "boolean", "description", "是否后台运行"));
        properties.put("name", Map.of("type", "string", "description", "本次后台 Agent 名称"));
        properties.put("teamName", Map.of("type", "string", "description", "可选;非空时把该 Agent 派入指定 Team"));
        return Map.of("type", "object", "properties", properties, "required", List.of("prompt", "description"));
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        String prompt = string(args, "prompt");
        String description = string(args, "description");
        if (prompt.isBlank() || description.isBlank()) {
            return Result.error("Agent 需要 prompt 和 description");
        }

        Optional<Agent.RunContext> current = Agent.currentRunContext();
        String teamName = string(args, "teamName");
        if (!teamName.isBlank()) {
            return executeTeamSpawn(ctx, args, prompt, description, current);
        }
        if (current.map(Agent.RunContext::subAgent).orElse(false)) {
            return Result.error("子 Agent 不能再次启动 Agent");
        }
        if (current.map(runCtx -> Fork.isForkContext(runCtx.conversation().getMessages())).orElse(false)) {
            return Result.error("Fork 子 Agent 不能再次启动 Agent");
        }

        Agent parent = parentAgent != null ? parentAgent : current.map(Agent.RunContext::agent).orElse(null);
        if (parent == null) {
            return Result.error("Agent 工具尚未绑定主 Agent");
        }

        String subagentType = string(args, "subagent_type");
        boolean fork = subagentType.isBlank();
        Definition definition;
        if (fork) {
            definition = catalog.forkDefinition();
        } else {
            definition = catalog.resolve(subagentType).orElse(null);
            if (definition == null) {
                return Result.error("未知 subagent_type: " + subagentType);
            }
        }

        boolean background = definition.background() || bool(args, "run_in_background") || fork;
        if (background && !backgroundEnabled) {
            return Result.error("SubAgent 后台能力已被配置关闭");
        }

        List<String> allowedTools = Filter.applyAgentToolFilter(new Filter.FilterParams(
                parent.registry().names(),
                definition.source().ordinal() + 1,
                background,
                definition.tools(),
                definition.disallowedTools()));
        Registry childRegistry = parent.registry().filtered(allowedTools);
        Agent child = Agent.builder()
                .client(parent.client())
                .registry(childRegistry)
                .version(parent.version())
                .engine(parent.engine())
                .runtime(SessionRuntime.empty(parent.contextWindow()))
                .systemPrompt(definition.systemPrompt())
                .maxTurns(definition.maxTurns())
                .permissionMode(definition.permissionMode())
                .dontAsk(definition.dontAsk())
                .approvalUpgrader((cancel, request) -> Optional.of(Outcome.DENY_ONCE))
                .subAgent(true)
                .toolContext(ctx == null ? ToolContext.root() : ctx)
                .build();

        ConversationManager childConversation;
        String taskText;
        if (fork) {
            ConversationManager parentConversation = current.map(Agent.RunContext::conversation).orElse(null);
            if (parentConversation == null) {
                return Result.error("Fork 路径需要主对话上下文");
            }
            childConversation = ConversationManager.fromMessages(
                    Fork.buildForkedMessages(parentConversation.getMessages(), prompt),
                    null,
                    null);
            taskText = "";
        } else {
            childConversation = new ConversationManager();
            taskText = prompt;
        }

        if (!fork && "worktree".equals(definition.isolation())) {
            if (worktreeManager == null) {
                return Result.error("worktree manager not configured");
            }
            try {
                return Result.ok(executeWithWorktree(ctx, child, childConversation, taskText));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.error("子 Agent 被中断");
            } catch (Exception e) {
                return Result.error("Worktree 子 Agent 失败: " + e.getMessage());
            }
        }

        if (background) {
            String id = taskManager.launch(child, childConversation, string(args, "name"), taskText);
            return Result.ok(json(Map.of("task_id", id, "status", "async_launched")));
        }

        try {
            String finalText = child.runToCompletion(new CancelToken(), childConversation, taskText, null);
            return Result.ok(finalText);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("子 Agent 被中断");
        }
    }

    private Result executeTeamSpawn(
            ToolContext ctx,
            Map<String, Object> args,
            String prompt,
            String description,
            Optional<Agent.RunContext> current) {
        if (teamHook == null) {
            return Result.error("Team spawn 尚未配置");
        }
        Optional<TeammateContext> teammate = teamHook.teammateContextOf(current.orElse(null));
        if (teammate.map(value -> value.backendType() == BackendType.IN_PROCESS).orElse(false)) {
            return Result.error(new InProcessTeammateNoSpawnException().getMessage());
        }
        Agent parent = parentAgent != null ? parentAgent : current.map(Agent.RunContext::agent).orElse(null);
        if (parent == null) {
            return Result.error("Agent 工具尚未绑定主 Agent");
        }
        try {
            String result = teamHook.spawnTeammate(new TeamSpawnRequest(
                    string(args, "teamName"),
                    string(args, "name"),
                    description,
                    prompt,
                    string(args, "subagent_type"),
                    string(args, "model"),
                    bool(args, "run_in_background"),
                    parent,
                    current.map(Agent.RunContext::conversation).orElse(null),
                    ctx == null ? ToolContext.root() : ctx));
            return Result.ok(result);
        } catch (Exception e) {
            return Result.error("Team spawn 失败: " + e.getMessage());
        }
    }

    private String executeWithWorktree(ToolContext ctx, Agent child, ConversationManager childConversation, String taskText)
            throws IOException, InterruptedException {
        String name = com.bluecode.worktree.WorktreeNaming.randomAgentName();
        var wt = worktreeManager.create(name, "HEAD", false);
        ToolContext parentContext = ctx == null ? ToolContext.root() : ctx;
        child.setToolContext(parentContext.withCwd(wt.path()));
        Path parentCwd = parentContext.cwd().orElseGet(() -> Path.of("").toAbsolutePath().normalize());
        String promptWithNotice = AgentWorktreeRunner.buildWorktreeNotice(parentCwd, wt.path())
                + "\n\n"
                + (taskText == null ? "" : taskText);
        String finalText;
        AutoCleanupReport report;
        try {
            finalText = child.runToCompletion(new CancelToken(), childConversation, promptWithNotice, null);
        } finally {
            report = worktreeManager.autoCleanup(name);
        }
        if (report.kept() && !report.path().isBlank()) {
            finalText += "\n[Worktree 保留: " + report.path() + ", 分支 " + report.branch() + "]";
        }
        return finalText;
    }

    private static String string(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean bool(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static String json(Map<String, Object> value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"json encode failed\"}";
        }
    }
}
