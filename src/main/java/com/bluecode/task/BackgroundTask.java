package com.bluecode.task;

import com.bluecode.agent.Agent;
import com.bluecode.agent.CancelToken;
import com.bluecode.conversation.ConversationManager;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public final class BackgroundTask {
    private final String id;
    private final String name;
    private final Agent subAgent;
    private final ConversationManager conversation;
    private final String task;
    private volatile Status status;
    private volatile String result;
    private volatile Throwable err;
    private final Instant startTime;
    private volatile Instant endTime;
    private volatile CancelToken cancelToken;
    private volatile Usage usage = new Usage(0, 0, 0, 0);
    private final AtomicInteger toolCount = new AtomicInteger();
    private volatile String lastActivity = "";

    BackgroundTask(String id, String name, Agent subAgent, ConversationManager conversation, String task,
                   CancelToken cancelToken) {
        this.id = id;
        this.name = name == null ? "" : name;
        this.subAgent = subAgent;
        this.conversation = conversation;
        this.task = task == null ? "" : task;
        this.cancelToken = cancelToken == null ? new CancelToken() : cancelToken;
        this.status = Status.RUNNING;
        this.startTime = Instant.now();
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Agent subAgent() {
        return subAgent;
    }

    public ConversationManager conversation() {
        return conversation;
    }

    public String task() {
        return task;
    }

    public Status status() {
        return status;
    }

    void status(Status status) {
        this.status = status;
    }

    public String result() {
        return result == null ? "" : result;
    }

    void result(String result) {
        this.result = result;
    }

    public Throwable err() {
        return err;
    }

    void err(Throwable err) {
        this.err = err;
    }

    public Instant startTime() {
        return startTime;
    }

    public Instant endTime() {
        return endTime;
    }

    void endNow() {
        this.endTime = Instant.now();
    }

    public CancelToken cancelToken() {
        return cancelToken;
    }

    void cancelToken(CancelToken cancelToken) {
        this.cancelToken = cancelToken;
    }

    public Usage usage() {
        return usage;
    }

    void addUsage(com.bluecode.agent.Usage eventUsage) {
        usage = usage.plus(eventUsage);
    }

    public int toolCount() {
        return toolCount.get();
    }

    void markTool(String name) {
        toolCount.incrementAndGet();
        lastActivity = name == null ? "" : name;
    }

    public String lastActivity() {
        return lastActivity == null ? "" : lastActivity;
    }
}
