package com.bluecode.teams;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

public final class FileLock {
    public static final int LOCK_MAX_RETRIES = 10;
    public static final Duration LOCK_STALE_AFTER = Duration.ofSeconds(10);
    public static final int LOCK_BACKOFF_MIN_MS = 5;
    public static final int LOCK_BACKOFF_MAX_MS = 100;

    private FileLock() {
    }

    public static AutoCloseable acquire(Path lockPath) throws IOException {
        IOException last = null;
        if (lockPath.getParent() != null) {
            Files.createDirectories(lockPath.getParent());
        }
        for (int i = 0; i < LOCK_MAX_RETRIES; i++) {
            try (OutputStream ignored = Files.newOutputStream(
                    lockPath,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                return () -> Files.deleteIfExists(lockPath);
            } catch (FileAlreadyExistsException e) {
                last = e;
                clearIfStale(lockPath);
                sleepBackoff();
            }
        }
        throw new IOException("获取文件锁失败: " + lockPath, last);
    }

    private static void clearIfStale(Path lockPath) throws IOException {
        if (!Files.exists(lockPath)) {
            return;
        }
        Instant modified = Files.getLastModifiedTime(lockPath).toInstant();
        if (modified.plus(LOCK_STALE_AFTER).isBefore(Instant.now())) {
            Files.deleteIfExists(lockPath);
        }
    }

    private static void sleepBackoff() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(LOCK_BACKOFF_MIN_MS, LOCK_BACKOFF_MAX_MS + 1L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
