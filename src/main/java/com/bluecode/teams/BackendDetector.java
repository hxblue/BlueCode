package com.bluecode.teams;

import com.bluecode.team.BackendType;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public final class BackendDetector {
    private BackendDetector() {
    }

    public static BackendType detect() {
        return detect(new SystemEnvProvider());
    }

    public static BackendType detect(EnvProvider env) {
        EnvProvider effective = env == null ? new SystemEnvProvider() : env;
        if (effective.getenv("TMUX") != null && !effective.getenv("TMUX").isBlank()) {
            return BackendType.TMUX;
        }
        if ("iTerm.app".equals(effective.getenv("TERM_PROGRAM")) && findOnPath(effective, "it2").isPresent()) {
            return BackendType.ITERM2;
        }
        if (findOnPath(effective, "tmux").isPresent()) {
            return BackendType.TMUX;
        }
        return BackendType.IN_PROCESS;
    }

    public static Optional<Path> findOnPath(EnvProvider env, String binary) {
        String path = env == null ? null : env.getenv("PATH");
        if (path == null || path.isBlank() || binary == null || binary.isBlank()) {
            return Optional.empty();
        }
        for (String item : path.split(File.pathSeparator)) {
            Path candidate = Path.of(item).resolve(binary);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return Optional.of(candidate);
            }
            Path exe = Path.of(item).resolve(binary + ".exe");
            if (Files.isRegularFile(exe) && Files.isExecutable(exe)) {
                return Optional.of(exe);
            }
        }
        return Optional.empty();
    }

    @FunctionalInterface
    public interface EnvProvider {
        String getenv(String key);
    }

    private static final class SystemEnvProvider implements EnvProvider {
        private final Map<String, String> env = System.getenv();

        @Override
        public String getenv(String key) {
            return env.get(key);
        }
    }
}
