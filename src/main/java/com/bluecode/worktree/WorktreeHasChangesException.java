package com.bluecode.worktree;

import java.io.IOException;

public final class WorktreeHasChangesException extends IOException {
    public WorktreeHasChangesException() {
        super("worktree has uncommitted changes or new commits");
    }
}
