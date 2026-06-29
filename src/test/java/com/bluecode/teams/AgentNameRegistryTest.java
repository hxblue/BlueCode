package com.bluecode.teams;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentNameRegistryTest {
    @Test
    void registerResolveAndReplaceBothDirections() {
        AgentNameRegistry registry = new AgentNameRegistry();

        registry.register("alice", "agent-1");
        assertEquals("agent-1", registry.resolve("alice").orElseThrow());
        assertEquals("alice", registry.nameOf("agent-1").orElseThrow());

        registry.register("alice", "agent-2");
        assertTrue(registry.nameOf("agent-1").isEmpty());
        assertEquals("agent-2", registry.resolve("alice").orElseThrow());

        registry.register("bob", "agent-2");
        assertTrue(registry.resolve("alice").isEmpty());
        assertEquals("bob", registry.nameOf("agent-2").orElseThrow());
    }
}
