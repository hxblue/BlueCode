package com.bluecode.team;

import com.bluecode.team.exceptions.TeamHasActiveMembersException;
import com.bluecode.team.exceptions.TeamNotFoundException;
import com.bluecode.teams.AgentNameRegistry;
import com.bluecode.teams.BackendFactory;
import com.bluecode.teams.BackendDetector;
import com.bluecode.teams.Mailbox;
import com.bluecode.teams.Message;
import com.bluecode.teams.MessageType;
import com.bluecode.worktree.ExitOptions;
import com.bluecode.worktree.WorktreeManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public final class TeamManager {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Team> teams = new HashMap<>();
    private final Path homeDir;
    private final Path projectRoot;
    private final Path teamsRoot;
    private final WorktreeManager worktreeManager;
    private final com.bluecode.task.Manager taskManager;
    private final AgentNameRegistry registry;

    public TeamManager(
            Path homeDir,
            Path projectRoot,
            WorktreeManager worktreeManager,
            com.bluecode.task.Manager taskManager,
            AgentNameRegistry registry) throws IOException {
        this.homeDir = (homeDir == null ? Path.of(System.getProperty("user.home")) : homeDir).toAbsolutePath().normalize();
        this.projectRoot = (projectRoot == null ? Path.of("").toAbsolutePath() : projectRoot).toAbsolutePath().normalize();
        this.worktreeManager = worktreeManager;
        this.taskManager = taskManager;
        this.registry = registry == null ? new AgentNameRegistry() : registry;
        this.teamsRoot = this.homeDir.resolve(".bluecode").resolve("teams");
        Files.createDirectories(teamsRoot);
        loadFromDisk();
    }

    public Optional<Team> get(String name) {
        lock.lock();
        try {
            return Optional.ofNullable(teams.get(Persistence.sanitize(name)));
        } finally {
            lock.unlock();
        }
    }

    public List<Team> list() {
        lock.lock();
        try {
            return teams.values().stream()
                    .sorted(Comparator.comparingLong(Team::createdAt))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    public Team create(String name, String description) throws IOException {
        String sanitizedBase = Persistence.sanitize(name);
        if (sanitizedBase.isBlank()) {
            throw new IllegalArgumentException("Team 名称 sanitize 后为空");
        }
        lock.lock();
        try {
            String sanitized = uniqueName(sanitizedBase);
            Path configDir = teamsRoot.resolve(sanitized);
            Files.createDirectories(configDir.resolve("mailbox"));
            BackendType backend = BackendDetector.detect();
            List<TeammateInfo> members = List.of(new TeammateInfo(
                    "lead",
                    "lead",
                    "",
                    "",
                    projectRoot.toString(),
                    "",
                    backend,
                    "",
                    false,
                    false,
                    ""));
            Team team = new Team(name, sanitized, description, "lead", backend, System.currentTimeMillis(), members, configDir);
            team.save();
            teams.put(sanitized, team);
            registry.register("lead", "lead");
            return team;
        } finally {
            lock.unlock();
        }
    }

    public void delete(String name, boolean force) throws IOException {
        Team team;
        lock.lock();
        try {
            team = teams.get(Persistence.sanitize(name));
            if (team == null) {
                throw new TeamNotFoundException(name);
            }
            if (!force && team.members().stream().anyMatch(this::activeNonLead)) {
                throw new TeamHasActiveMembersException(name);
            }
            cleanupMembers(team);
            deleteRecursively(team.configDir());
            teams.remove(team.sanitizedName());
        } finally {
            lock.unlock();
        }
    }

    public String spawnTeammate(com.bluecode.agent.TeamSpawnRequest request) throws IOException {
        return new SpawnTeammate(this, worktreeManager, taskManager, registry, projectRoot).spawnTeammate(request);
    }

    public void handleTaskDone(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return;
        }
        for (Team team : list()) {
            Optional<TeammateInfo> member = team.memberByAgentId(agentId);
            if (member.isEmpty()) {
                continue;
            }
            try {
                team.setMemberActive(member.get().name(), false);
                new Mailbox(team.mailboxDir()).write(team.leadAgentId(), new Message(
                        member.get().name(),
                        team.leadAgentId(),
                        MessageType.IDLE_NOTIFICATION,
                        member.get().name() + " idle",
                        "队员 " + member.get().name() + " 已空闲。",
                        Map.of("agentId", agentId, "team", team.name()),
                        0,
                        false));
            } catch (IOException e) {
                System.err.printf("[team] warn: task done hook failed for %s: %s%n", agentId, e.getMessage());
            }
            return;
        }
    }

    public List<LeadMessage> pollLeadMailboxes() {
        List<LeadMessage> result = new ArrayList<>();
        for (Team team : list()) {
            try {
                Mailbox mailbox = new Mailbox(team.mailboxDir());
                var unread = mailbox.readUnread(team.leadAgentId());
                if (unread.messages().isEmpty()) {
                    continue;
                }
                mailbox.markRead(team.leadAgentId(), unread.indices());
                for (Message msg : unread.messages()) {
                    result.add(new LeadMessage(
                            team.name(),
                            msg.from(),
                            msg.type(),
                            msg.summary(),
                            msg.content(),
                            msg.timestamp()));
                }
            } catch (IOException e) {
                System.err.printf("[team] warn: poll lead mailbox failed for %s: %s%n", team.name(), e.getMessage());
            }
        }
        return List.copyOf(result);
    }

    public AgentNameRegistry registry() {
        return registry;
    }

    public Path teamsRoot() {
        return teamsRoot;
    }

    public BackendFactory.Deps backendDeps() {
        return new BackendFactory.Deps(projectRoot, null, taskManager);
    }

    private void loadFromDisk() throws IOException {
        try (Stream<Path> stream = Files.list(teamsRoot)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                Path config = dir.resolve("config.json");
                try {
                    Optional<Team.Snapshot> snapshot = Persistence.readJson(config, Team.Snapshot.class);
                    snapshot.ifPresent(value -> {
                        Team team = Team.fromSnapshot(value, dir);
                        teams.put(team.sanitizedName(), team);
                        for (TeammateInfo member : team.members()) {
                            registry.register(member.name(), member.agentId());
                        }
                    });
                } catch (Exception e) {
                    System.err.printf("[team] warn: skip broken team %s: %s%n", dir.getFileName(), e.getMessage());
                }
            }
        }
    }

    private String uniqueName(String base) {
        String name = base;
        int suffix = 2;
        while (teams.containsKey(name) || Files.exists(teamsRoot.resolve(name))) {
            name = base + "-" + suffix++;
        }
        return name;
    }

    private boolean activeNonLead(TeammateInfo member) {
        return !"lead".equals(member.name()) && member.isActive() != Boolean.FALSE;
    }

    private void cleanupMembers(Team team) {
        for (TeammateInfo member : team.members()) {
            if ("lead".equals(member.name())) {
                continue;
            }
            try {
                BackendFactory.create(member.backendType(), backendDeps()).kill(member.paneId(), member.agentId());
            } catch (Exception e) {
                System.err.printf("[team] warn: kill %s failed: %s%n", member.name(), e.getMessage());
            }
            tryDeleteSession(member);
            tryRemoveWorktree(team, member);
            registry.unregister(member.name());
        }
    }

    private void tryDeleteSession(TeammateInfo member) {
        if (member.sessionDir().isBlank()) {
            return;
        }
        try {
            deleteRecursively(Path.of(member.sessionDir()));
        } catch (IOException e) {
            System.err.printf("[team] warn: remove session %s failed: %s%n", member.sessionDir(), e.getMessage());
        }
    }

    private void tryRemoveWorktree(Team team, TeammateInfo member) {
        if (worktreeManager == null) {
            return;
        }
        String name = "team-" + team.sanitizedName() + "/" + member.name();
        try {
            worktreeManager.remove(name, new ExitOptions(true));
        } catch (IOException e) {
            System.err.printf("[team] warn: remove worktree %s failed: %s%n", name, e.getMessage());
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(normalized)) {
            for (Path item : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(item);
            }
        }
    }

    public record LeadMessage(
            String teamName,
            String from,
            MessageType type,
            String summary,
            String content,
            long timestamp) {
    }
}
