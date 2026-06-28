package com.bluecode.tool;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterTest {
    @Test
    void removesAgentByDefault() {
        List<String> result = Filter.applyAgentToolFilter(new Filter.FilterParams(
                List.of("ReadFile", "Agent", "Bash"),
                1,
                false,
                List.of(),
                List.of()));

        assertEquals(List.of("ReadFile", "Bash"), result);
    }

    @Test
    void backgroundKeepsOnlyAsyncAllowedAndMcp() {
        List<String> result = Filter.applyAgentToolFilter(new Filter.FilterParams(
                List.of("ReadFile", "TaskList", "mcp__server__tool", "Agent"),
                1,
                true,
                List.of(),
                List.of()));

        assertEquals(List.of("ReadFile", "mcp__server__tool"), result);
    }

    @Test
    void appliesDisallowedThenWhitelist() {
        List<String> result = Filter.applyAgentToolFilter(new Filter.FilterParams(
                List.of("ReadFile", "Bash", "Grep"),
                1,
                false,
                List.of("ReadFile", "Bash"),
                List.of("Bash")));

        assertEquals(List.of("ReadFile"), result);
        assertTrue(Filter.isMcpOrSkill("mcp__x__y"));
        assertFalse(Filter.isMcpOrSkill("TaskGet"));
    }
}
