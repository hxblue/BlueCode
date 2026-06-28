package com.bluecode.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

public final class CommandRegistry {
    private final Map<String, Command> byName = new HashMap<>();
    private final List<Command> visible = new ArrayList<>();

    public void register(Command command) {
        validate(command);
        List<String> keys = new ArrayList<>();
        keys.add(command.name());
        keys.addAll(command.aliases());
        for (String key : keys) {
            if (byName.containsKey(key)) {
                throw new IllegalStateException("命令名/别名冲突: " + key);
            }
        }
        for (String key : keys) {
            byName.put(key, command);
        }
        if (!command.hidden()) {
            visible.add(command);
            visible.sort((left, right) -> left.name().compareTo(right.name()));
        }
    }

    public Optional<Command> lookup(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public void removeIf(Predicate<Command> predicate) {
        if (predicate == null) {
            return;
        }
        byName.entrySet().removeIf(entry -> predicate.test(entry.getValue()));
        visible.removeIf(predicate);
    }

    public List<Command> visible() {
        return List.copyOf(visible);
    }

    public List<Command> prefixMatch(String prefix) {
        String normalized = normalizePrefix(prefix);
        return visible.stream()
                .filter(command -> command.name().startsWith(normalized))
                .toList();
    }

    private void validate(Command command) {
        if (command == null) {
            throw new IllegalArgumentException("命令不能为空");
        }
        validateKey(command.name(), "命令名");
        for (String alias : command.aliases()) {
            validateKey(alias, "命令别名");
        }
        if (command.description() == null || command.description().isBlank()) {
            throw new IllegalArgumentException("命令描述不能为空: " + command.name());
        }
        if (command.kind() == null) {
            throw new IllegalArgumentException("命令类型不能为空: " + command.name());
        }
        if (command.handler() == null) {
            throw new IllegalArgumentException("命令处理函数不能为空: " + command.name());
        }
    }

    private void validateKey(String key, String label) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        if (!key.equals(key.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(label + "必须全小写: " + key);
        }
        if (key.startsWith("/")) {
            throw new IllegalArgumentException(label + "不能包含 / 前缀: " + key);
        }
    }

    private String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String normalized = prefix.strip().toLowerCase(Locale.ROOT);
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }
}
