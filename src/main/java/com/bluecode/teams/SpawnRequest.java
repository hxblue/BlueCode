package com.bluecode.teams;

public record SpawnRequest(
        String teamName,
        String memberName,
        String agentId,
        String worktreePath,
        String sessionDir,
        String agentType,
        String model,
        String initialPrompt,
        boolean planModeRequired,
        Object subAgent,
        Object conv,
        Object taskManager) {
    public SpawnRequest {
        teamName = teamName == null ? "" : teamName;
        memberName = memberName == null ? "" : memberName;
        agentId = agentId == null ? "" : agentId;
        worktreePath = worktreePath == null ? "" : worktreePath;
        sessionDir = sessionDir == null ? "" : sessionDir;
        agentType = agentType == null ? "" : agentType;
        model = model == null ? "" : model;
        initialPrompt = initialPrompt == null ? "" : initialPrompt;
    }
}
