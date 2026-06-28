package com.bluecode.skill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ActiveSkills {
    private final Object lock = new Object();
    private final List<ActiveEntry> entries = new ArrayList<>();
    private final Map<String, Integer> index = new HashMap<>();

    public void activate(String name, String body) {
        String normalized = SkillCatalog.normalizeName(name);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("skill name 不能为空");
        }
        ActiveEntry entry = new ActiveEntry(normalized, body == null ? "" : body);
        synchronized (lock) {
            Integer existing = index.get(normalized);
            if (existing == null) {
                index.put(normalized, entries.size());
                entries.add(entry);
            } else {
                entries.set(existing, entry);
            }
        }
    }

    public void clear() {
        synchronized (lock) {
            entries.clear();
            index.clear();
        }
    }

    public List<ActiveEntry> snapshot() {
        synchronized (lock) {
            return List.copyOf(entries);
        }
    }

    public List<String> names() {
        synchronized (lock) {
            return List.copyOf(entries.stream().map(ActiveEntry::name).toList());
        }
    }

    public String renderActiveContext() {
        List<ActiveEntry> copy = snapshot();
        if (copy.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("## Active Skills");
        for (ActiveEntry entry : copy) {
            builder.append("\n\n### ")
                    .append(entry.name())
                    .append("\n\n")
                    .append(entry.body().strip());
        }
        return builder.toString();
    }

    public record ActiveEntry(String name, String body) {
        public ActiveEntry {
            name = SkillCatalog.normalizeName(name);
            body = body == null ? "" : body;
        }
    }
}
