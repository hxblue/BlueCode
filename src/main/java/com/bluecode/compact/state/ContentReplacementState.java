package com.bluecode.compact.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class ContentReplacementState {
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> seenIds = new HashSet<>();
    private final Map<String, String> replacements = new HashMap<>();

    public enum Decision {
        KEPT,
        REPLACED,
        SKIP
    }

    public record DecisionResult(Decision decision, String preview) {
    }

    public String decideOnce(String id, String original, Supplier<DecisionResult> decide) {
        String safeId = id == null ? "" : id;
        String safeOriginal = original == null ? "" : original;
        lock.lock();
        try {
            if (seenIds.contains(safeId)) {
                return replacements.getOrDefault(safeId, safeOriginal);
            }
            DecisionResult result = decide.get();
            if (result == null || result.decision() == null) {
                return safeOriginal;
            }
            return switch (result.decision()) {
                case KEPT -> {
                    seenIds.add(safeId);
                    yield safeOriginal;
                }
                case REPLACED -> {
                    String preview = result.preview() == null ? safeOriginal : result.preview();
                    seenIds.add(safeId);
                    replacements.put(safeId, preview);
                    yield preview;
                }
                case SKIP -> safeOriginal;
            };
        } finally {
            lock.unlock();
        }
    }

    public void reset() {
        lock.lock();
        try {
            seenIds.clear();
            replacements.clear();
        } finally {
            lock.unlock();
        }
    }
}
