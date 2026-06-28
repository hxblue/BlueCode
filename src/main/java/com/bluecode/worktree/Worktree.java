package com.bluecode.worktree;

import java.nio.file.Path;
import java.time.Instant;

public record Worktree(
        String name,
        Path path,
        String branch,
        String basedOn,
        String headCommit,
        Instant created,
        boolean manual) {
    public Worktree {
        name = name == null ? "" : name;
        path = path == null ? Path.of("").toAbsolutePath().normalize() : path.toAbsolutePath().normalize();
        branch = branch == null ? "" : branch;
        basedOn = basedOn == null ? "" : basedOn;
        headCommit = headCommit == null ? "" : headCommit;
        created = created == null ? Instant.now() : created;
    }
}
