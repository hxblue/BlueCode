package com.bluecode.tui;

import com.bluecode.command.Builtins;
import com.bluecode.command.CommandRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionMenuTest {
    @Test
    void slashShowsAllAndPrefixFiltersByName() {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        CompletionMenu menu = new CompletionMenu();

        menu.update("/", registry);
        String all = menu.render(120);
        assertTrue(menu.active());
        assertTrue(all.contains("/help"));
        assertEquals(8, menu.lineCount());

        menu.update("/s", registry);
        String filtered = menu.render(120);
        assertTrue(filtered.contains("/session"));
        assertTrue(filtered.contains("/status"));
        assertEquals("session", menu.selected().name());
    }

    @Test
    void zeroMatchStaysActiveButHasNoSelection() {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        CompletionMenu menu = new CompletionMenu();

        menu.update("/zzz", registry);

        assertTrue(menu.active());
        assertNull(menu.selected());
        assertTrue(menu.render(80).contains("无匹配"));
    }

    @Test
    void leadingSpaceDoesNotActivateMenu() {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        CompletionMenu menu = new CompletionMenu();

        menu.update(" /", registry);

        assertTrue(!menu.active());
    }
}
