package com.bluecode.teams;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

public final class AgentNameRegistry {
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, String> byName = new HashMap<>();
    private final Map<String, String> byId = new HashMap<>();

    public void register(String name, String agentId) {
        if (name == null || name.isBlank() || agentId == null || agentId.isBlank()) {
            return;
        }
        lock.lock();
        try {
            String oldId = byName.remove(name);
            if (oldId != null) {
                byId.remove(oldId);
            }
            String oldName = byId.remove(agentId);
            if (oldName != null) {
                byName.remove(oldName);
            }
            byName.put(name, agentId);
            byId.put(agentId, name);
        } finally {
            lock.unlock();
        }
    }

    public void unregister(String name) {
        lock.lock();
        try {
            String id = byName.remove(name);
            if (id != null) {
                byId.remove(id);
            }
        } finally {
            lock.unlock();
        }
    }

    public void unregisterByAgentId(String agentId) {
        lock.lock();
        try {
            String name = byId.remove(agentId);
            if (name != null) {
                byName.remove(name);
            }
        } finally {
            lock.unlock();
        }
    }

    public Optional<String> resolve(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return Optional.empty();
        }
        lock.lock();
        try {
            if (byName.containsKey(nameOrId)) {
                return Optional.of(byName.get(nameOrId));
            }
            if (byId.containsKey(nameOrId)) {
                return Optional.of(nameOrId);
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    public Optional<String> nameOf(String agentId) {
        lock.lock();
        try {
            return Optional.ofNullable(byId.get(agentId));
        } finally {
            lock.unlock();
        }
    }

    public Map<String, String> snapshot() {
        lock.lock();
        try {
            return Map.copyOf(byName);
        } finally {
            lock.unlock();
        }
    }
}
