package com.bluecode.compact.state;

import com.bluecode.compact.CompactConstants;

import java.util.concurrent.locks.ReentrantLock;

public final class AutoCompactTrackingState {
    private final ReentrantLock lock = new ReentrantLock();
    private int consecutiveFailures;

    public void recordSuccess() {
        lock.lock();
        try {
            consecutiveFailures = 0;
        } finally {
            lock.unlock();
        }
    }

    public void recordFailure() {
        lock.lock();
        try {
            consecutiveFailures++;
        } finally {
            lock.unlock();
        }
    }

    public boolean tripped() {
        lock.lock();
        try {
            return consecutiveFailures >= CompactConstants.MAX_CONSECUTIVE_AUTO_COMPACT_FAILURES;
        } finally {
            lock.unlock();
        }
    }

    public int consecutiveFailures() {
        lock.lock();
        try {
            return consecutiveFailures;
        } finally {
            lock.unlock();
        }
    }

    public void reset() {
        lock.lock();
        try {
            consecutiveFailures = 0;
        } finally {
            lock.unlock();
        }
    }
}
