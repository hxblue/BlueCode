package com.bluecode.tui;

import com.bluecode.command.WorktreeAccessor;
import com.bluecode.command.WorktreeSummary;
import com.bluecode.worktree.ExitAction;
import com.bluecode.worktree.ExitOptions;
import com.bluecode.worktree.ExitReport;
import com.bluecode.worktree.Worktree;
import com.bluecode.worktree.WorktreeManager;
import com.bluecode.worktree.WorktreeSession;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public final class TuiWorktreeAccessor implements WorktreeAccessor {
    private final WorktreeManager manager;
    private final Consumer<Path> activeCwdSetter;

    public TuiWorktreeAccessor(WorktreeManager manager, Consumer<Path> activeCwdSetter) {
        this.manager = manager;
        this.activeCwdSetter = activeCwdSetter;
    }

    @Override
    public CreateResult create(String name) throws IOException {
        Worktree wt = manager.create(name, "HEAD", true);
        return new CreateResult(wt.path().toString(), wt.branch());
    }

    @Override
    public List<WorktreeSummary> list() {
        WorktreeSession session = manager.currentSession();
        String activeName = session == null ? "" : session.worktreeName();
        return manager.list().stream()
                .map(wt -> new WorktreeSummary(
                        wt.name(),
                        wt.path().toString(),
                        wt.branch(),
                        wt.name().equals(activeName),
                        wt.manual()))
                .toList();
    }

    @Override
    public void enter(String name) throws IOException {
        WorktreeSession session = manager.enter(name);
        activeCwdSetter.accept(Path.of(session.worktreePath()));
    }

    @Override
    public ExitResult exit(boolean remove, boolean discard) throws IOException {
        WorktreeSession session = manager.currentSession();
        if (session == null) {
            throw new IOException("当前没有活跃 Worktree session");
        }
        ExitReport report = manager.exit(
                session.worktreeName(),
                remove ? ExitAction.REMOVE : ExitAction.KEEP,
                new ExitOptions(discard));
        activeCwdSetter.accept(null);
        return new ExitResult(report.removed(), report.path(), report.branch());
    }

    @Override
    public void remove(String name, boolean discard) throws IOException {
        WorktreeSession session = manager.currentSession();
        manager.remove(name, new ExitOptions(discard));
        if (session != null && session.worktreeName().equals(name)) {
            activeCwdSetter.accept(null);
        }
    }
}
