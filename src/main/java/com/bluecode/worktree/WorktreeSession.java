package com.bluecode.worktree;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WorktreeSession(
        @JsonProperty("original_cwd") String originalCwd,
        @JsonProperty("worktree_path") String worktreePath,
        @JsonProperty("worktree_name") String worktreeName,
        @JsonProperty("original_branch") String originalBranch,
        @JsonProperty("original_head_commit") String originalHeadCommit,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("hook_based") boolean hookBased) {
    public WorktreeSession {
        originalCwd = originalCwd == null ? "" : originalCwd;
        worktreePath = worktreePath == null ? "" : worktreePath;
        worktreeName = worktreeName == null ? "" : worktreeName;
        originalBranch = originalBranch == null ? "" : originalBranch;
        originalHeadCommit = originalHeadCommit == null ? "" : originalHeadCommit;
        sessionId = sessionId == null ? "" : sessionId;
    }
}
