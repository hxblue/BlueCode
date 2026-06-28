package com.bluecode.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public final class WorktreeManager {
    private static final List<String> DEFAULT_SYMLINK_DIRS = List.of("node_modules", ".venv", "vendor");

    private final Path repoRoot;
    private final Path worktreeDir;
    private final Path sessionFile;
    private final List<String> symlinkDirs;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Worktree> active = new HashMap<>();
    private WorktreeSession currentSession;

    public WorktreeManager(Path repoRoot) throws IOException {
        this(repoRoot, DEFAULT_SYMLINK_DIRS);
    }

    public WorktreeManager(Path repoRoot, List<String> symlinkDirs) throws IOException {
        this.repoRoot = (repoRoot == null ? Path.of("") : repoRoot).toAbsolutePath().normalize();
        validateRepoRoot(this.repoRoot);
        this.worktreeDir = this.repoRoot.resolve(".bluecode").resolve("worktrees");
        this.sessionFile = this.repoRoot.resolve(".bluecode").resolve("worktree_session.json");
        this.symlinkDirs = symlinkDirs == null ? DEFAULT_SYMLINK_DIRS : List.copyOf(symlinkDirs);
        Files.createDirectories(worktreeDir);
        loadSession();
        restoreActiveFromDisk();
    }

    public Worktree create(String name, String baseRef, boolean manual) throws IOException {
        WorktreeSlug.validate(name);
        lock.lock();
        try {
            if (active.containsKey(name)) {
                throw new IOException("Worktree 已存在: " + name);
            }
            String flatSlug = WorktreeSlug.flatten(name);
            Path wtPath = worktreeDir.resolve(flatSlug).toAbsolutePath().normalize();
            String branch = "worktree-" + flatSlug;
            String base = baseRef == null || baseRef.isBlank() ? "HEAD" : baseRef;

            if (Files.exists(wtPath)) {
                String head = GitHelper.resolveHeadShaFromFS(wtPath)
                        .orElseThrow(() -> new IOException("无法快速恢复 Worktree HEAD: " + wtPath));
                Worktree wt = new Worktree(name, wtPath, branch, base, head, createdTime(wtPath), manual);
                active.put(name, wt);
                return wt;
            }

            try {
                GitHelper.runGit(repoRoot, "worktree", "add", "-B", branch, wtPath.toString(), base);
            } catch (IOException e) {
                deleteIfEmptyOrCreated(wtPath);
                throw e;
            }
            PostCreationSetup.run(repoRoot, wtPath, symlinkDirs);
            String head = GitHelper.runGit(wtPath, "rev-parse", "HEAD").strip();
            Worktree wt = new Worktree(name, wtPath, branch, base, head, Instant.now(), manual);
            active.put(name, wt);
            return wt;
        } finally {
            lock.unlock();
        }
    }

    public WorktreeSession enter(String name) throws IOException {
        lock.lock();
        try {
            Worktree wt = requireActive(name);
            String branch = safeGit(repoRoot, "rev-parse", "--abbrev-ref", "HEAD");
            String head = safeGit(repoRoot, "rev-parse", "HEAD");
            WorktreeSession session = new WorktreeSession(
                    Path.of("").toAbsolutePath().normalize().toString(),
                    wt.path().toString(),
                    wt.name(),
                    branch,
                    head,
                    UUID.randomUUID().toString(),
                    false);
            currentSession = session;
            SessionStore.save(sessionFile, session);
            return session;
        } finally {
            lock.unlock();
        }
    }

    public ExitReport exit(String name, ExitAction action, ExitOptions opts) throws IOException {
        lock.lock();
        try {
            if (currentSession == null || !currentSession.worktreeName().equals(name)) {
                throw new IOException("只能退出当前 Worktree session: " + name);
            }
            Worktree wt = requireActive(name);
            ExitReport report = removeIfRequested(wt, action == ExitAction.REMOVE, opts);
            currentSession = null;
            SessionStore.clear(sessionFile);
            return report;
        } finally {
            lock.unlock();
        }
    }

    public void remove(String name, ExitOptions opts) throws IOException {
        lock.lock();
        try {
            Worktree wt = requireActive(name);
            removeIfRequested(wt, true, opts);
            if (currentSession != null && currentSession.worktreeName().equals(name)) {
                currentSession = null;
                SessionStore.clear(sessionFile);
            }
        } finally {
            lock.unlock();
        }
    }

    public AutoCleanupReport autoCleanup(String name) throws IOException {
        lock.lock();
        try {
            Worktree wt = active.get(name);
            if (wt == null) {
                return new AutoCleanupReport(false, "", "");
            }
            if (wt.manual()) {
                return new AutoCleanupReport(true, wt.path().toString(), wt.branch());
            }
            if (GitHelper.hasWorktreeChanges(wt.path(), wt.headCommit())) {
                return new AutoCleanupReport(true, wt.path().toString(), wt.branch());
            }
            removeIfRequested(wt, true, new ExitOptions(true));
            return new AutoCleanupReport(false, wt.path().toString(), wt.branch());
        } finally {
            lock.unlock();
        }
    }

    public List<String> sweepStale(Instant cutoff) {
        List<String> removed = new ArrayList<>();
        Instant threshold = cutoff == null ? Instant.EPOCH : cutoff;
        lock.lock();
        try (Stream<Path> stream = Files.list(worktreeDir)) {
            for (Path path : stream.filter(Files::isDirectory).toList()) {
                String flatName = path.getFileName().toString();
                if (!WorktreeNaming.EPHEMERAL_PATTERN.matcher(flatName).matches()) {
                    continue;
                }
                if (Files.getLastModifiedTime(path).toInstant().isAfter(threshold)) {
                    continue;
                }
                if (currentSession != null
                        && Path.of(currentSession.worktreePath()).toAbsolutePath().normalize()
                        .equals(path.toAbsolutePath().normalize())) {
                    continue;
                }
                String name = flatName.replace("+", "/");
                Worktree wt = active.get(name);
                if (wt == null) {
                    String head = GitHelper.resolveHeadShaFromFS(path).orElse("HEAD");
                    wt = new Worktree(name, path, "worktree-" + flatName, head, head, createdTime(path), false);
                    active.put(name, wt);
                }
                if (GitHelper.hasWorktreeChanges(path, wt.headCommit()) || GitHelper.hasUnpushedCommits(path)) {
                    continue;
                }
                try {
                    removeIfRequested(wt, true, new ExitOptions(true));
                    removed.add(name);
                } catch (IOException e) {
                    System.err.printf("worktree: sweep skip %s: %s%n", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.printf("worktree: sweep failed: %s%n", e.getMessage());
        } finally {
            lock.unlock();
        }
        return List.copyOf(removed);
    }

    public List<Worktree> list() {
        lock.lock();
        try {
            return active.values().stream()
                    .sorted(Comparator.comparing(Worktree::name))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    public Optional<Worktree> get(String name) {
        lock.lock();
        try {
            return Optional.ofNullable(active.get(name));
        } finally {
            lock.unlock();
        }
    }

    public WorktreeSession currentSession() {
        lock.lock();
        try {
            return currentSession;
        } finally {
            lock.unlock();
        }
    }

    private ExitReport removeIfRequested(Worktree wt, boolean remove, ExitOptions opts) throws IOException {
        boolean discard = opts != null && opts.discardChanges();
        if (remove && !discard && GitHelper.hasWorktreeChanges(wt.path(), wt.headCommit())) {
            throw new WorktreeHasChangesException();
        }
        if (remove) {
            GitHelper.runGit(repoRoot, "worktree", "remove", "--force", wt.path().toString());
            sleepBriefly();
            try {
                GitHelper.runGit(repoRoot, "branch", "-D", wt.branch());
            } catch (IOException e) {
                System.err.printf("worktree: delete branch %s failed: %s%n", wt.branch(), e.getMessage());
            }
            active.remove(wt.name());
        }
        return new ExitReport(remove, wt.path().toString(), wt.branch());
    }

    private Worktree requireActive(String name) throws IOException {
        Worktree wt = active.get(name);
        if (wt == null) {
            throw new IOException("未知 Worktree: " + name);
        }
        return wt;
    }

    private void validateRepoRoot(Path root) throws IOException {
        String actual = GitHelper.runGit(root, "rev-parse", "--show-toplevel").strip();
        Path gitRoot = Path.of(actual).toAbsolutePath().normalize();
        if (!gitRoot.equals(root)) {
            throw new IOException("repoRoot 不是 git 仓库根目录: " + root + " != " + gitRoot);
        }
    }

    private void loadSession() throws IOException {
        Optional<WorktreeSession> loaded = SessionStore.load(sessionFile);
        if (loaded.isEmpty()) {
            currentSession = null;
            return;
        }
        WorktreeSession session = loaded.get();
        if (session.worktreePath().isBlank() || !Files.isDirectory(Path.of(session.worktreePath()))) {
            System.err.println("worktree: session worktree gone, cleared");
            currentSession = null;
            SessionStore.clear(sessionFile);
            return;
        }
        currentSession = session;
    }

    private void restoreActiveFromDisk() throws IOException {
        if (!Files.isDirectory(worktreeDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(worktreeDir)) {
            for (Path path : stream.filter(Files::isDirectory).toList()) {
                String flat = path.getFileName().toString();
                Optional<String> head = GitHelper.resolveHeadShaFromFS(path);
                if (head.isEmpty()) {
                    continue;
                }
                String name = flat.replace("+", "/");
                active.put(name, new Worktree(
                        name,
                        path,
                        "worktree-" + flat,
                        head.get(),
                        head.get(),
                        createdTime(path),
                        false));
            }
        }
    }

    private Instant createdTime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException e) {
            return Instant.now();
        }
    }

    private String safeGit(Path root, String... args) {
        try {
            return GitHelper.runGit(root, args).strip();
        } catch (IOException e) {
            return "";
        }
    }

    private void deleteIfEmptyOrCreated(Path path) {
        if (path == null || !Files.exists(path) || !path.startsWith(worktreeDir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        } catch (IOException ignored) {
            // 创建失败清理属于兜底动作,不覆盖原始错误。
        }
    }

    private void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
