package com.bluecode.agent;

import com.bluecode.compact.Recovery;
import com.bluecode.compact.state.AutoCompactTrackingState;
import com.bluecode.compact.state.ContentReplacementState;
import com.bluecode.compact.state.SessionContext;
import com.bluecode.skill.ActiveSkills;
import com.bluecode.hook.HookEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class SessionRuntime {
    public final ContentReplacementState replacement;
    public final Recovery.RecoveryState recovery;
    public final AutoCompactTrackingState autoTracking;
    public final ActiveSkills activeSkills;
    public volatile SessionContext session;
    public volatile int contextWindow;
    public volatile HookEngine hookEngine;

    private final ReentrantLock anchorLock = new ReentrantLock();
    private final ReentrantLock reminderLock = new ReentrantLock();
    private final List<String> pendingReminders = new ArrayList<>();
    private final AtomicLong turnCount = new AtomicLong();
    private long usageAnchor;
    private int anchorMsgLen;

    public SessionRuntime(
            ContentReplacementState replacement,
            Recovery.RecoveryState recovery,
            AutoCompactTrackingState autoTracking,
            SessionContext session,
            int contextWindow) {
        this.replacement = replacement == null ? new ContentReplacementState() : replacement;
        this.recovery = recovery == null ? new Recovery.RecoveryState() : recovery;
        this.autoTracking = autoTracking == null ? new AutoCompactTrackingState() : autoTracking;
        this.activeSkills = new ActiveSkills();
        this.session = session == null ? defaultSession() : session;
        this.contextWindow = contextWindow;
    }

    public static SessionRuntime create(Path workspace, int contextWindow) throws IOException {
        return new SessionRuntime(
                new ContentReplacementState(),
                new Recovery.RecoveryState(),
                new AutoCompactTrackingState(),
                SessionContext.create(workspace),
                contextWindow);
    }

    public static SessionRuntime empty(int contextWindow) {
        try {
            return create(Path.of("").toAbsolutePath(), contextWindow);
        } catch (IOException e) {
            throw new IllegalStateException("创建会话上下文失败: " + e.getMessage(), e);
        }
    }

    public long getUsageAnchor() {
        anchorLock.lock();
        try {
            return usageAnchor;
        } finally {
            anchorLock.unlock();
        }
    }

    public int getAnchorMsgLen() {
        anchorLock.lock();
        try {
            return anchorMsgLen;
        } finally {
            anchorLock.unlock();
        }
    }

    public void updateAnchor(long anchor, int msgLen) {
        anchorLock.lock();
        try {
            usageAnchor = Math.max(0, anchor);
            anchorMsgLen = Math.max(0, msgLen);
        } finally {
            anchorLock.unlock();
        }
    }

    public long incrementTurnCount() {
        return turnCount.incrementAndGet();
    }

    public long turnCount() {
        return turnCount.get();
    }

    public void appendReminders(List<String> reminders) {
        if (reminders == null || reminders.isEmpty()) {
            return;
        }
        reminderLock.lock();
        try {
            for (String reminder : reminders) {
                if (reminder != null && !reminder.isBlank()) {
                    pendingReminders.add(reminder);
                }
            }
        } finally {
            reminderLock.unlock();
        }
    }

    public List<String> takeReminders() {
        reminderLock.lock();
        try {
            if (pendingReminders.isEmpty()) {
                return List.of();
            }
            List<String> result = List.copyOf(pendingReminders);
            pendingReminders.clear();
            return result;
        } finally {
            reminderLock.unlock();
        }
    }

    public void resetForNewSession(SessionContext session) {
        replacement.reset();
        recovery.reset();
        autoTracking.reset();
        activeSkills.clear();
        turnCount.set(0);
        if (hookEngine != null) {
            hookEngine.resetForNewSession();
        }
        reminderLock.lock();
        try {
            pendingReminders.clear();
        } finally {
            reminderLock.unlock();
        }
        this.session = session == null ? defaultSession() : session;
        anchorLock.lock();
        try {
            usageAnchor = 0;
            anchorMsgLen = 0;
        } finally {
            anchorLock.unlock();
        }
    }

    private static SessionContext defaultSession() {
        try {
            return SessionContext.create(Path.of("").toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("创建会话上下文失败: " + e.getMessage(), e);
        }
    }
}
