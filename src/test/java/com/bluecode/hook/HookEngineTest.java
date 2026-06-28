package com.bluecode.hook;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookEngineTest {
    @Test
    void dispatchCollectsPromptsInRuleOrder() {
        HookEngine engine = new HookEngine(List.of(
                prompt("a", "一"),
                prompt("b", "二")
        ), List.of("test"), new HookExecutor());

        DispatchResult result = engine.dispatch(Event.SESSION_START, new Payload(Map.of()));

        assertFalse(result.blocked());
        assertEquals(List.of("一", "二"), result.injectedPrompts());
    }

    @Test
    void onlyOnceSkipsUntilReset() {
        HookRule once = new HookRule("once", Event.SESSION_START, null,
                new Action.Prompt("只一次"), true, false, Duration.ofSeconds(1), "test");
        HookEngine engine = new HookEngine(List.of(once), List.of(), new HookExecutor());

        assertEquals(1, engine.dispatch(Event.SESSION_START, new Payload(Map.of())).injectedPrompts().size());
        assertEquals(0, engine.dispatch(Event.SESSION_START, new Payload(Map.of())).injectedPrompts().size());
        engine.resetForNewSession();
        assertEquals(1, engine.dispatch(Event.SESSION_START, new Payload(Map.of())).injectedPrompts().size());
    }

    @Test
    void blockingEventStopsAtFirstBlock() {
        HookEngine engine = new HookEngine(List.of(
                new HookRule("block", Event.PRE_TOOL_USE, null,
                        new Action.Shell("echo no >&2; exit 2"), false, false, Duration.ofSeconds(5), "test"),
                new HookRule("later", Event.PRE_TOOL_USE, null,
                        new Action.Prompt("不应执行"), false, false, Duration.ofSeconds(1), "test")
        ), List.of(), new HookExecutor());

        DispatchResult result = engine.dispatch(Event.PRE_TOOL_USE, new Payload(Map.of()));

        assertTrue(result.blocked());
        assertEquals("block", result.blockingHookName());
        assertEquals(List.of(), result.injectedPrompts());
    }

    private HookRule prompt(String name, String text) {
        return new HookRule(name, Event.SESSION_START, null,
                new Action.Prompt(text), false, false, Duration.ofSeconds(1), "test");
    }
}
