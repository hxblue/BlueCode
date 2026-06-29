package com.bluecode.teams;

import com.bluecode.team.BackendType;
import com.bluecode.teams.inprocess.InProcessBackend;
import com.bluecode.teams.iterm2.Iterm2Backend;
import com.bluecode.teams.tmux.TmuxBackend;

import java.nio.file.Path;

public final class BackendFactory {
    private BackendFactory() {
    }

    public static Backend create(BackendType type) {
        return create(type, Deps.empty());
    }

    public static Backend create(BackendType type, Deps deps) {
        BackendType effective = type == null ? BackendType.IN_PROCESS : type;
        Deps effectiveDeps = deps == null ? Deps.empty() : deps;
        return switch (effective) {
            case TMUX -> new TmuxBackend(effectiveDeps);
            case ITERM2 -> new Iterm2Backend(effectiveDeps);
            case IN_PROCESS -> new InProcessBackend(effectiveDeps);
        };
    }

    public record Deps(Path projectRoot, Path jarPath, com.bluecode.task.Manager taskManager) {
        public static Deps empty() {
            return new Deps(Path.of("").toAbsolutePath().normalize(), null, null);
        }

        public Deps {
            projectRoot = projectRoot == null ? Path.of("").toAbsolutePath().normalize() : projectRoot.toAbsolutePath().normalize();
        }
    }
}
