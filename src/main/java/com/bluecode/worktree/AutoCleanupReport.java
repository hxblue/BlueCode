package com.bluecode.worktree;

public record AutoCleanupReport(boolean kept, String path, String branch) {
}
