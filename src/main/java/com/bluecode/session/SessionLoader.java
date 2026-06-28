package com.bluecode.session;

import com.bluecode.conversation.Message;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class SessionLoader {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SessionLoader() {
    }

    public static List<Message> load(Path sessionDir) throws IOException {
        return loadWithMetadata(sessionDir).messages();
    }

    public static LoadedSession loadWithMetadata(Path sessionDir) throws IOException {
        Path jsonl = sessionDir.resolve("conversation.jsonl");
        List<Message> messages = new ArrayList<>();
        long lastTs = 0;
        if (!Files.isRegularFile(jsonl)) {
            return new LoadedSession(List.of(), Instant.EPOCH);
        }
        try (BufferedReader reader = Files.newBufferedReader(jsonl, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Entry entry;
                try {
                    entry = MAPPER.readValue(line, Entry.class);
                } catch (Exception ignored) {
                    continue;
                }
                if ("compact".equals(entry.type())) {
                    messages.clear();
                    lastTs = Math.max(lastTs, entry.ts());
                    continue;
                }
                Message message = entry.toMessage();
                if (message != null) {
                    messages.add(message);
                    lastTs = Math.max(lastTs, entry.ts());
                }
            }
        }
        return new LoadedSession(truncateOrphanedToolCallComplete(messages), Instant.ofEpochSecond(Math.max(0, lastTs)));
    }

    public static List<Message> truncateOrphanedToolCallComplete(List<Message> source) {
        List<Message> messages = new ArrayList<>(source == null ? List.of() : source);
        if (messages.isEmpty()) {
            return List.of();
        }
        Message last = messages.getLast();
        if (last.role() == Message.Role.ASSISTANT && !last.toolCalls().isEmpty()) {
            messages.removeLast();
        }
        return List.copyOf(messages);
    }

    public record LoadedSession(List<Message> messages, Instant lastTimestamp) {
        public LoadedSession {
            messages = messages == null ? List.of() : List.copyOf(messages);
            lastTimestamp = lastTimestamp == null ? Instant.EPOCH : lastTimestamp;
        }
    }
}
