package com.bluecode.teams;

import com.bluecode.team.BackendType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackendDetectorTest {
    @Test
    void tmuxEnvironmentWins() {
        BackendDetector.EnvProvider env = key -> Map.of("TMUX", "/tmp/tmux", "PATH", "").get(key);

        assertEquals(BackendType.TMUX, BackendDetector.detect(env));
    }

    @Test
    void emptyEnvironmentFallsBackToInProcess() {
        BackendDetector.EnvProvider env = key -> "";

        assertEquals(BackendType.IN_PROCESS, BackendDetector.detect(env));
    }
}
