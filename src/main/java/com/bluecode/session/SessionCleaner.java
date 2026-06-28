package com.bluecode.session;

import com.bluecode.compact.state.SessionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.stream.Stream;
import java.util.logging.Logger;

public final class SessionCleaner {
    private static final Logger LOGGER = Logger.getLogger(SessionCleaner.class.getName());

    private SessionCleaner() {
    }

    public static void cleanExpired(Path sessionsDir, Duration maxAge) {
        if (sessionsDir == null || maxAge == null || !Files.isDirectory(sessionsDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(sessionsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                cleanOne(dir, maxAge);
            }
        } catch (IOException e) {
            LOGGER.warning("扫描会话目录失败: " + e.getMessage());
        }
    }

    private static void cleanOne(Path dir, Duration maxAge) {
        String id = dir.getFileName().toString();
        if (!SessionContext.isNewFormat(id)) {
            return;
        }
        Instant created = SessionContext.parseSessionTime(id)
                .atZone(ZoneId.systemDefault())
                .toInstant();
        if (!Duration.between(created, Instant.now()).minus(maxAge).isPositive()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOGGER.warning("清理过期会话失败: " + dir + ": " + e.getMessage());
        }
    }
}
