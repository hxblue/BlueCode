package com.bluecode.hook;

import java.util.List;

public record DispatchResult(boolean blocked, String reason, String blockingHookName, List<String> injectedPrompts) {
    public DispatchResult {
        reason = reason == null ? "" : reason;
        blockingHookName = blockingHookName == null ? "" : blockingHookName;
        injectedPrompts = List.copyOf(injectedPrompts == null ? List.of() : injectedPrompts);
    }

    public static DispatchResult empty() {
        return new DispatchResult(false, "", "", List.of());
    }
}
