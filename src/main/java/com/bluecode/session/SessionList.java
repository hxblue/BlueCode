package com.bluecode.session;

import com.bluecode.compact.state.SessionContext;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class SessionList {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionList() {
    }

    public static List<SessionInfo> list(Path sessionsDir) throws IOException {
        if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        List<SessionInfo> sessions = new ArrayList<>();
        try (Stream<Path> stream = Files.list(sessionsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                SessionInfo info = inspect(dir);
                if (info != null) {
                    sessions.add(info);
                }
            }
        }
        sessions.sort(Comparator.comparing(SessionInfo::modifiedAt).reversed());
        return List.copyOf(sessions);
    }

    private static SessionInfo inspect(Path dir) {
        String id = dir.getFileName().toString();
        if (!SessionContext.isNewFormat(id)) {
            return null;
        }
        Path jsonl = dir.resolve("conversation.jsonl");
        if (!Files.isRegularFile(jsonl)) {
            return null;
        }
        String title = "未命名会话";
        String model = "";
        try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Entry entry;
                try {
                    entry = MAPPER.readValue(line, Entry.class);
                } catch (Exception ignored) {
                    continue;
                }
                if (model.isBlank() && entry.model() != null) {
                    model = entry.model();
                }
                if ("user".equals(entry.role()) && entry.content() != null && !entry.content().isBlank()) {
                    title = truncate(entry.content().replaceAll("\\s+", " "), 50);
                    break;
                }
            }
            Instant modifiedAt = Files.getLastModifiedTime(jsonl).toInstant();
            long size = Files.size(jsonl);
            return new SessionInfo(id, title, modifiedAt, model, size, dir.toAbsolutePath().normalize());
        } catch (IOException e) {
            return null;
        }
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }
}
