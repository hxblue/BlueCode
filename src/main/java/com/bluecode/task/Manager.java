package com.bluecode.task;

import com.bluecode.agent.Agent;
import com.bluecode.agent.CancelToken;
import com.bluecode.agent.Event;
import com.bluecode.agent.Phase;
import com.bluecode.conversation.ConversationManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class Manager {
    private final Object mu = new Object();
    private final Map<String, BackgroundTask> tasks = new HashMap<>();
    private final Map<String, String> byName = new HashMap<>();
    private final List<Consumer<String>> taskDoneCallbacks = new ArrayList<>();
    private final BlockingQueue<String> donePub = new LinkedBlockingQueue<>(32);
    private final AtomicLong counter = new AtomicLong();
    private com.bluecode.teams.AgentNameRegistry nameRegistry;

    public String launch(Agent agent, ConversationManager conversation, String name, String taskText) {
        BackgroundTask task = createTask(nextId(), agent, conversation, name, taskText, new CancelToken());
        runTask(task, taskText);
        return task.id();
    }

    public String launchWithId(String id, Agent agent, ConversationManager conversation, String name, String taskText) {
        String effectiveId = id == null || id.isBlank() ? nextId() : id;
        BackgroundTask task = createTask(effectiveId, agent, conversation, name, taskText, new CancelToken());
        runTask(task, taskText);
        return task.id();
    }

    public Optional<BackgroundTask> get(String id) {
        synchronized (mu) {
            return Optional.ofNullable(tasks.get(id));
        }
    }

    public List<BackgroundTask> list() {
        synchronized (mu) {
            return tasks.values().stream()
                    .sorted(Comparator.comparing(BackgroundTask::startTime))
                    .toList();
        }
    }

    public boolean stop(String id) {
        BackgroundTask task;
        synchronized (mu) {
            task = tasks.get(id);
        }
        if (task == null) {
            return false;
        }
        task.cancelToken().cancel();
        return true;
    }

    public String sendMessage(String name, String message) {
        BackgroundTask task;
        synchronized (mu) {
            String id = nameRegistry == null ? byName.get(name) : nameRegistry.resolve(name).orElse(byName.get(name));
            task = id == null ? null : tasks.get(id);
        }
        if (task == null) {
            throw new IllegalArgumentException("找不到后台 Agent: " + name);
        }
        if (task.status() != Status.COMPLETED) {
            throw new IllegalStateException("后台 Agent 尚未完成: " + name);
        }
        task.cancelToken(new CancelToken());
        task.status(Status.RUNNING);
        task.err(null);
        task.result("");
        runTask(task, message);
        return task.id();
    }

    public BlockingQueue<String> subscribeDone() {
        return donePub;
    }

    public void setNameRegistry(com.bluecode.teams.AgentNameRegistry registry) {
        synchronized (mu) {
            this.nameRegistry = registry;
        }
    }

    public Optional<BackgroundTask> getByName(String name) {
        synchronized (mu) {
            String id = nameRegistry == null ? byName.get(name) : nameRegistry.resolve(name).orElse(byName.get(name));
            return Optional.ofNullable(id == null ? null : tasks.get(id));
        }
    }

    public void onTaskDone(Consumer<String> callback) {
        if (callback == null) {
            return;
        }
        synchronized (mu) {
            taskDoneCallbacks.add(callback);
        }
    }

    private BackgroundTask createTask(String id, Agent agent, ConversationManager conversation, String name,
                                      String taskText, CancelToken cancelToken) {
        BackgroundTask task = new BackgroundTask(id, name, agent, conversation, taskText, cancelToken);
        synchronized (mu) {
            tasks.put(id, task);
            if (name != null && !name.isBlank()) {
                byName.put(name, id);
                if (nameRegistry != null) {
                    nameRegistry.register(name, id);
                }
            }
        }
        return task;
    }

    private void runTask(BackgroundTask task, String taskText) {
        Thread.startVirtualThread(() -> {
            BlockingQueue<Event> events = new LinkedBlockingQueue<>();
            Thread aggregator = Thread.startVirtualThread(() -> aggregate(events, task));
            try {
                String text = task.subAgent().runToCompletion(task.cancelToken(), task.conversation(), taskText, events);
                task.endNow();
                if (task.cancelToken().isCancelled()) {
                    task.status(Status.CANCELLED);
                } else {
                    task.status(Status.COMPLETED);
                    task.result(text);
                }
            } catch (Throwable t) {
                task.endNow();
                task.err(t);
                task.status(task.cancelToken().isCancelled() ? Status.CANCELLED : Status.FAILED);
            } finally {
                try {
                    aggregator.join(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (!donePub.offer(task.id())) {
                    System.err.printf("[task] warn: done queue full, dropping notification for %s%n", task.id());
                }
                notifyTaskDone(task.id());
            }
        });
    }

    private void notifyTaskDone(String id) {
        List<Consumer<String>> callbacks;
        synchronized (mu) {
            callbacks = List.copyOf(taskDoneCallbacks);
        }
        for (Consumer<String> callback : callbacks) {
            try {
                callback.accept(id);
            } catch (Exception e) {
                System.err.printf("[task] warn: done callback failed for %s: %s%n", id, e.getMessage());
            }
        }
    }

    private void aggregate(BlockingQueue<Event> events, BackgroundTask task) {
        boolean done = false;
        while (!done) {
            try {
                Event event = events.take();
                switch (event) {
                    case Event.Tool tool -> {
                        if (tool.event().phase() == Phase.START) {
                            task.markTool(tool.event().name());
                        }
                    }
                    case Event.UsageReport usage -> task.addUsage(usage.usage());
                    case Event.Done ignored -> done = true;
                    case Event.Failed ignored -> done = true;
                    default -> {
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                done = true;
            }
        }
    }

    private String nextId() {
        long value = counter.incrementAndGet();
        long mixed = System.nanoTime() ^ value;
        return "task_%08x".formatted(mixed & 0xffffffffL);
    }
}
