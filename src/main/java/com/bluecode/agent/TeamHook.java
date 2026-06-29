package com.bluecode.agent;

import java.io.IOException;
import java.util.Optional;

public interface TeamHook {
    String spawnTeammate(TeamSpawnRequest request) throws IOException;

    default Optional<TeammateContext> teammateContextOf(Agent.RunContext context) {
        return context == null ? Optional.empty() : Optional.ofNullable(context.teammateContext());
    }
}
