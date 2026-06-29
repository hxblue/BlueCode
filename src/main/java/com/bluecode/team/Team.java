package com.bluecode.team;

import com.bluecode.team.exceptions.MemberExistsException;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

public final class Team {
    private final ReentrantLock lock = new ReentrantLock();
    private final String name;
    private final String sanitizedName;
    private final String leadAgentId;
    private final Path configDir;
    private final Path configPath;
    private final Path tasksPath;
    private final Path mailboxDir;
    private String description;
    private BackendType backend;
    private long createdAt;
    private List<TeammateInfo> members;

    public Team(
            String name,
            String sanitizedName,
            String description,
            String leadAgentId,
            BackendType backend,
            long createdAt,
            List<TeammateInfo> members,
            Path configDir) {
        this.name = name == null ? "" : name;
        this.sanitizedName = sanitizedName == null ? "" : sanitizedName;
        this.description = description == null ? "" : description;
        this.leadAgentId = leadAgentId == null || leadAgentId.isBlank() ? "lead" : leadAgentId;
        this.backend = backend == null ? BackendType.IN_PROCESS : backend;
        this.createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
        this.members = new ArrayList<>(members == null ? List.of() : members);
        this.configDir = configDir.toAbsolutePath().normalize();
        this.configPath = this.configDir.resolve("config.json");
        this.tasksPath = this.configDir.resolve("tasks.json");
        this.mailboxDir = this.configDir.resolve("mailbox");
    }

    public static Team fromSnapshot(Snapshot snapshot, Path configDir) {
        return new Team(
                snapshot.name(),
                snapshot.sanitizedName(),
                snapshot.description(),
                snapshot.leadAgentId(),
                snapshot.backend(),
                snapshot.createdAt(),
                snapshot.members(),
                configDir);
    }

    public boolean addMember(TeammateInfo info) throws IOException {
        lock.lock();
        try {
            Persistence.reloadFromDiskLocked(this);
            if (members.stream().anyMatch(member -> member.name().equals(info.name()))) {
                throw new MemberExistsException(info.name());
            }
            members.add(info);
            saveLocked();
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean setMemberActive(String name, boolean active) throws IOException {
        lock.lock();
        try {
            Persistence.reloadFromDiskLocked(this);
            for (int i = 0; i < members.size(); i++) {
                TeammateInfo member = members.get(i);
                if (member.name().equals(name) || member.agentId().equals(name)) {
                    members.set(i, member.withActive(active));
                    saveLocked();
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    public boolean removeMember(String name) throws IOException {
        lock.lock();
        try {
            Persistence.reloadFromDiskLocked(this);
            boolean removed = members.removeIf(member -> member.name().equals(name) || member.agentId().equals(name));
            if (removed) {
                saveLocked();
            }
            return removed;
        } finally {
            lock.unlock();
        }
    }

    public Optional<TeammateInfo> memberByName(String name) {
        lock.lock();
        try {
            return members.stream().filter(member -> member.name().equals(name)).findFirst();
        } finally {
            lock.unlock();
        }
    }

    public Optional<TeammateInfo> memberByAgentId(String id) {
        lock.lock();
        try {
            return members.stream().filter(member -> member.agentId().equals(id)).findFirst();
        } finally {
            lock.unlock();
        }
    }

    public List<TeammateInfo> members() {
        lock.lock();
        try {
            return members.stream()
                    .sorted(Comparator.comparing(TeammateInfo::name))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    void replaceMembersFromDisk(List<TeammateInfo> diskMembers) {
        members = new ArrayList<>(diskMembers == null ? List.of() : diskMembers);
    }

    void save() throws IOException {
        lock.lock();
        try {
            saveLocked();
        } finally {
            lock.unlock();
        }
    }

    private void saveLocked() throws IOException {
        Persistence.atomicWriteJson(configPath, snapshot());
    }

    public Snapshot snapshot() {
        return new Snapshot(name, sanitizedName, description, leadAgentId, backend, createdAt, members());
    }

    public String name() {
        return name;
    }

    public String sanitizedName() {
        return sanitizedName;
    }

    public String description() {
        return description;
    }

    public String leadAgentId() {
        return leadAgentId;
    }

    public BackendType backend() {
        return backend;
    }

    public long createdAt() {
        return createdAt;
    }

    public Path configDir() {
        return configDir;
    }

    public Path configPath() {
        return configPath;
    }

    public Path tasksPath() {
        return tasksPath;
    }

    public Path mailboxDir() {
        return mailboxDir;
    }

    public record Snapshot(
            @JsonProperty("name") String name,
            @JsonProperty("sanitizedName") String sanitizedName,
            @JsonProperty("description") String description,
            @JsonProperty("leadAgentId") String leadAgentId,
            @JsonProperty("backend") BackendType backend,
            @JsonProperty("createdAt") long createdAt,
            @JsonProperty("members") List<TeammateInfo> members) {
        public Snapshot {
            name = name == null ? "" : name;
            sanitizedName = sanitizedName == null ? "" : sanitizedName;
            description = description == null ? "" : description;
            leadAgentId = leadAgentId == null || leadAgentId.isBlank() ? "lead" : leadAgentId;
            backend = backend == null ? BackendType.IN_PROCESS : backend;
            createdAt = createdAt <= 0 ? System.currentTimeMillis() : createdAt;
            members = members == null ? List.of() : List.copyOf(members);
        }
    }
}
