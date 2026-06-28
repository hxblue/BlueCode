package com.bluecode.agent;

import com.bluecode.conversation.ConversationManager;
import com.bluecode.tool.ToolContext;
import com.bluecode.worktree.AutoCleanupReport;
import com.bluecode.worktree.Worktree;
import com.bluecode.worktree.WorktreeManager;
import com.bluecode.worktree.WorktreeNaming;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;

public final class AgentWorktreeRunner {
    private final WorktreeManager manager;

    public AgentWorktreeRunner(WorktreeManager manager) {
        this.manager = manager;
    }

    public String executeWithWorktree(
            ToolContext parentContext,
            Agent child,
            ConversationManager childConversation,
            String taskText,
            BlockingQueue<Event> events) throws IOException, InterruptedException {
        if (manager == null) {
            throw new IOException("worktree manager not configured");
        }
        String name = WorktreeNaming.randomAgentName();
        Worktree wt = manager.create(name, "HEAD", false);
        ToolContext baseContext = parentContext == null ? ToolContext.root() : parentContext;
        Path parentCwd = baseContext.cwd().orElseGet(() -> Path.of("").toAbsolutePath().normalize());
        String finalText = "";
        AutoCleanupReport cleanup;
        try {
            child.setToolContext(baseContext.withCwd(wt.path()));
            String prompt = buildWorktreeNotice(parentCwd, wt.path()) + "\n\n" + (taskText == null ? "" : taskText);
            finalText = child.runToCompletion(new CancelToken(), childConversation, prompt, events);
        } finally {
            cleanup = manager.autoCleanup(name);
        }
        return appendCleanupNotice(finalText, cleanup);
    }

    public String appendCleanupNotice(String finalText, AutoCleanupReport report) {
        if (report == null || !report.kept() || report.path().isBlank()) {
            return finalText;
        }
        return (finalText == null ? "" : finalText)
                + "\n[Worktree 保留: " + report.path() + ", 分支 " + report.branch() + "]";
    }

    public static String buildWorktreeNotice(Path parentCwd, Path wtPath) {
        return """
                <worktree-context>
                你当前在一个独立的 Git Worktree 副本中工作,与父 Agent 隔离。
                - 父目录: %s
                - 你的工作目录: %s
                - 父 Agent 提到的绝对路径如果基于父目录,需要转换成本地 Worktree 路径再读写。
                - 编辑文件前必须先在本地 Worktree 重新 ReadFile 一次,避免使用过时内容。
                </worktree-context>
                """.formatted(
                parentCwd == null ? "" : parentCwd.toAbsolutePath().normalize(),
                wtPath == null ? "" : wtPath.toAbsolutePath().normalize()).strip();
    }
}
