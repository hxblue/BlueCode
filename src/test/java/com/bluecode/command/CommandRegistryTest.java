package com.bluecode.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistryTest {
    @Test
    void registerOkAndLookupIsCaseInsensitive() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help", List.of("h"), false));

        assertTrue(registry.lookup("help").isPresent());
        assertTrue(registry.lookup("HELP").isPresent());
        assertTrue(registry.lookup("h").isPresent());
    }

    @Test
    void registerDuplicateNameThrows() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help", List.of(), false));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> registry.register(command("help", List.of(), false)));

        assertTrue(error.getMessage().contains("help"));
    }

    @Test
    void registerDuplicateAliasThrows() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("help", List.of("h"), false));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> registry.register(command("history", List.of("h"), false)));

        assertTrue(error.getMessage().contains("h"));
    }

    @Test
    void visibleSortedAndHiddenExcluded() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("status", List.of(), false));
        registry.register(command("help", List.of(), false));
        registry.register(command("secret", List.of(), true));

        assertEquals(List.of("help", "status"), registry.visible().stream().map(Command::name).toList());
        assertTrue(registry.lookup("secret").isPresent());
    }

    @Test
    void prefixMatchUsesVisibleCommandNameOnly() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(command("status", List.of(), false));
        registry.register(command("session", List.of("ss"), false));
        registry.register(command("memory", List.of("sneaky"), false));

        assertEquals(List.of("session", "status"), registry.prefixMatch("/s").stream().map(Command::name).toList());
    }

    private Command command(String name, List<String> aliases, boolean hidden) {
        return new Command(name, aliases, "描述", Kind.LOCAL, hidden, (cancelled, ui) -> {
        });
    }
}
