package com.bluecode.hook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

public final class HookEngine {
    private final List<HookRule> rules;
    private final List<String> sources;
    private final HookExecutor executor;
    private final ReentrantLock onceLock = new ReentrantLock();
    private final Set<String> onceFired = new HashSet<>();

    public HookEngine(List<HookRule> rules, List<String> sources, HookExecutor executor) {
        this.rules = List.copyOf(rules == null ? List.of() : rules);
        this.sources = List.copyOf(sources == null ? List.of() : sources);
        this.executor = executor == null ? new HookExecutor() : executor;
    }

    public static HookEngine empty() {
        return new HookEngine(List.of(), List.of(), new HookExecutor());
    }

    public DispatchResult dispatch(Event event, Payload payload) {
        if (event == null || rules.isEmpty()) {
            return DispatchResult.empty();
        }
        List<String> prompts = new ArrayList<>();
        for (HookRule rule : rules) {
            if (rule.event() != event || alreadyFired(rule)) {
                continue;
            }
            if (!ConditionEvaluator.evaluate(rule.condition(), payload)) {
                continue;
            }
            if (rule.async()) {
                markOnce(rule);
                Thread.startVirtualThread(() -> {
                    ExecutionResult result = runSafely(rule, payload, false);
                    if (result.error() != null) {
                        logFailure(rule, result.error());
                    }
                });
                continue;
            }

            ExecutionResult result = runSafely(rule, payload, event.isBlocking());
            markOnce(rule);
            if (result.error() != null) {
                logFailure(rule, result.error());
                continue;
            }
            if (result.prompt() != null && !result.prompt().isBlank()) {
                prompts.add(result.prompt());
            }
            if (event.isBlocking() && result.blocked()) {
                return new DispatchResult(true, result.reason(), rule.name(), prompts);
            }
        }
        return new DispatchResult(false, "", "", prompts);
    }

    public void resetForNewSession() {
        onceLock.lock();
        try {
            onceFired.clear();
        } finally {
            onceLock.unlock();
        }
    }

    public List<HookRule> rules() {
        return rules;
    }

    public List<String> sources() {
        return sources;
    }

    private boolean alreadyFired(HookRule rule) {
        if (!rule.onlyOnce()) {
            return false;
        }
        onceLock.lock();
        try {
            return onceFired.contains(rule.name());
        } finally {
            onceLock.unlock();
        }
    }

    private void markOnce(HookRule rule) {
        if (!rule.onlyOnce()) {
            return;
        }
        onceLock.lock();
        try {
            onceFired.add(rule.name());
        } finally {
            onceLock.unlock();
        }
    }

    private ExecutionResult runSafely(HookRule rule, Payload payload, boolean blocking) {
        try {
            return executor.run(rule, payload, blocking);
        } catch (Throwable t) {
            return new ExecutionResult(false, "", "", t);
        }
    }

    private void logFailure(HookRule rule, Throwable error) {
        String reason = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
        System.err.printf("[hook %s] %s failed: %s%n", rule.name(), rule.event().wireName(), reason);
    }
}
