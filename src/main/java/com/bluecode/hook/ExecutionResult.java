package com.bluecode.hook;

public record ExecutionResult(boolean blocked, String reason, String prompt, Throwable error) {
    public static ExecutionResult empty() {
        return new ExecutionResult(false, "", "", null);
    }
}
