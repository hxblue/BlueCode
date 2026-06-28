package com.bluecode.agent;

public record CompactEvent(CompactPhase phase, long before, long after, Throwable error) implements Event {
}
