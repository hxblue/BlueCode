package com.bluecode.tool;

import java.nio.file.Path;
import java.util.Optional;

public record ToolContext(Optional<Path> cwd) {
    public ToolContext {
        cwd = cwd == null ? Optional.empty() : cwd.map(path -> path.toAbsolutePath().normalize());
    }

    public static ToolContext root() {
        return new ToolContext(Optional.empty());
    }

    public ToolContext withCwd(Path dir) {
        if (dir == null) {
            return this;
        }
        return new ToolContext(Optional.of(dir.toAbsolutePath().normalize()));
    }

    public Path resolvePath(String value) {
        if (value == null || value.isBlank()) {
            return cwd.orElseGet(() -> Path.of("").toAbsolutePath().normalize());
        }
        Path raw = Path.of(value);
        if (raw.isAbsolute()) {
            return raw.normalize();
        }
        Path base = cwd.orElseGet(() -> Path.of("").toAbsolutePath().normalize());
        return base.resolve(raw).normalize();
    }
}
