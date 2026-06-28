package com.bluecode.compact.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.SplittableRandom;
import java.util.logging.Logger;

public record SessionContext(String sessionId, Path sessionDir, Path spillDir) {
    private static final Logger LOGGER = Logger.getLogger(SessionContext.class.getName());
    private static final DateTimeFormatter SESSION_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static SessionContext create(Path workspace) throws IOException {
        Path root = workspace == null ? Path.of("").toAbsolutePath() : workspace.toAbsolutePath().normalize();
        String sessionId = newSessionId();
        Path sessionDir = root.resolve(".bluecode").resolve("sessions").resolve(sessionId);
        Path spillDir = sessionDir.resolve("tool-results");
        Files.createDirectories(spillDir);
        return new SessionContext(sessionId, sessionDir, spillDir);
    }

    public static SessionContext open(Path workspace, String sessionId) throws IOException {
        Path root = workspace == null ? Path.of("").toAbsolutePath() : workspace.toAbsolutePath().normalize();
        Path sessionDir = root.resolve(".bluecode").resolve("sessions").resolve(sessionId);
        if (!Files.isDirectory(sessionDir)) {
            throw new IOException("会话目录不存在: " + sessionDir);
        }
        return new SessionContext(sessionId, sessionDir, sessionDir.resolve("tool-results"));
    }

    public static String newSessionId() {
        byte[] bytes = new byte[4];
        try {
            SecureRandom.getInstanceStrong().nextBytes(bytes);
        } catch (Exception e) {
            LOGGER.warning("安全随机数不可用，使用会话级降级随机数: " + e.getMessage());
            int value = new SplittableRandom(System.nanoTime()).nextInt();
            bytes[0] = (byte) (value >>> 24);
            bytes[1] = (byte) (value >>> 16);
            bytes[2] = (byte) (value >>> 8);
            bytes[3] = (byte) value;
        }
        return SESSION_TIME_FORMAT.format(LocalDateTime.now()) + "-" + HexFormat.of().formatHex(bytes).substring(0, 4);
    }

    public static LocalDateTime parseSessionTime(String sessionId) {
        if (sessionId == null || sessionId.length() < 15) {
            throw new DateTimeParseException("session id 长度不足", String.valueOf(sessionId), 0);
        }
        return LocalDateTime.parse(sessionId.substring(0, 15), SESSION_TIME_FORMAT);
    }

    public static boolean isNewFormat(String sessionId) {
        if (sessionId == null || !sessionId.matches("\\d{8}-\\d{6}-[0-9a-f]{4}")) {
            return false;
        }
        try {
            parseSessionTime(sessionId);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
