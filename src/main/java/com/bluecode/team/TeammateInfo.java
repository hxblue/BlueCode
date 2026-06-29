package com.bluecode.team;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TeammateInfo(
        @JsonProperty("name") String name,
        @JsonProperty("agentId") String agentId,
        @JsonProperty("agentType") String agentType,
        @JsonProperty("model") String model,
        @JsonProperty("worktreePath") String worktreePath,
        @JsonProperty("branch") String branch,
        @JsonProperty("backendType") BackendType backendType,
        @JsonProperty("paneId") String paneId,
        @JsonProperty("isActive") Boolean isActive,
        @JsonProperty("planModeRequired") boolean planModeRequired,
        @JsonProperty("sessionDir") String sessionDir) {
    public TeammateInfo {
        name = name == null ? "" : name;
        agentId = agentId == null ? "" : agentId;
        agentType = agentType == null ? "" : agentType;
        model = model == null ? "" : model;
        worktreePath = worktreePath == null ? "" : worktreePath;
        branch = branch == null ? "" : branch;
        backendType = backendType == null ? BackendType.IN_PROCESS : backendType;
        paneId = paneId == null ? "" : paneId;
        sessionDir = sessionDir == null ? "" : sessionDir;
    }

    public TeammateInfo withActive(boolean active) {
        return new TeammateInfo(
                name,
                agentId,
                agentType,
                model,
                worktreePath,
                branch,
                backendType,
                paneId,
                active,
                planModeRequired,
                sessionDir);
    }
}
