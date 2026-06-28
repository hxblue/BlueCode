package com.bluecode.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CancelToken {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "bluecode-cancel-timer");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable callback : callbacks) {
                callback.run();
            }
        }
    }

    public void onCancel(Runnable callback) {
        if (callback == null) {
            return;
        }
        callbacks.add(callback);
        if (isCancelled()) {
            callback.run();
        }
    }

    public CancelToken withTimeout(Duration timeout) {
        CancelToken child = new CancelToken();
        onCancel(child::cancel);
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            ScheduledFuture<?> task = TIMER.schedule(child::cancel, timeout.toMillis(), TimeUnit.MILLISECONDS);
            child.onCancel(() -> task.cancel(false));
        }
        return child;
    }
}
