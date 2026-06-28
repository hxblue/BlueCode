package com.bluecode.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicSystemTest {
    @Test
    void cacheControlOnlyLivesOnStableSystemBlock() {
        List<Map<String, Object>> blocks = AnthropicClient.buildSystemBlocks(new SystemPrompt("stable", "environment"));

        assertEquals(2, blocks.size());
        assertEquals("stable", blocks.get(0).get("text"));
        assertTrue(blocks.get(0).containsKey("cache_control"));
        assertEquals(Map.of("type", "ephemeral"), blocks.get(0).get("cache_control"));
        assertEquals("environment", blocks.get(1).get("text"));
        assertFalse(blocks.get(1).containsKey("cache_control"));
    }
}
