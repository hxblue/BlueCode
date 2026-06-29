package com.bluecode.teams;

public record SpawnResult(String paneId, String agentId) {
    public SpawnResult {
        paneId = paneId == null ? "" : paneId;
        agentId = agentId == null ? "" : agentId;
    }
}
