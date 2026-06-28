package com.bluecode.agent;

import com.bluecode.permission.Outcome;

import java.util.Optional;

@FunctionalInterface
public interface ApprovalUpgrader {
    Optional<Outcome> upgrade(CancelToken cancel, ApprovalRequest request);
}
