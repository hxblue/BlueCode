package com.bluecode.team;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

public final class Persistence {
    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9._-]+");

    private Persistence() {
    }

    public static String sanitize(String name) {
        if (name == null) {
            return "";
        }
        String value = UNSAFE.matcher(name).replaceAll("-");
        value = value.replaceAll("^-+", "").replaceAll("-+$", "");
        return value;
    }

    public static void atomicWriteJson(Path path, Object value) throws IOException {
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static <T> Optional<T> readJson(Path path, Class<T> type) throws IOException {
        if (path == null || !Files.exists(path)) {
            return Optional.empty();
        }
        return Optional.of(MAPPER.readValue(Files.readAllBytes(path), type));
    }

    public static void reloadFromDiskLocked(Team team) {
        if (team == null) {
            return;
        }
        try {
            Optional<Team.Snapshot> snapshot = readJson(team.configPath(), Team.Snapshot.class);
            snapshot.ifPresent(value -> team.replaceMembersFromDisk(value.members()));
        } catch (IOException ignored) {
            // reload 是跨进程兜底；读失败时保留当前内存态，避免覆盖有效状态。
        }
    }
}
