package com.bluecode.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class SessionStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionStore() {
    }

    public static Optional<WorktreeSession> load(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return Optional.empty();
        }
        String text = Files.readString(path, StandardCharsets.UTF_8).strip();
        if (text.isEmpty() || "null".equals(text)) {
            return Optional.empty();
        }
        return Optional.of(MAPPER.readValue(text, WorktreeSession.class));
    }

    public static void save(Path path, WorktreeSession session) throws IOException {
        if (path == null) {
            throw new IOException("session path 不能为空");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = session == null ? "null" : MAPPER.writeValueAsString(session);
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailed) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void clear(Path path) throws IOException {
        save(path, null);
    }
}
