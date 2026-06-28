package com.bluecode.command;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public record Command(
        String name,
        List<String> aliases,
        String description,
        Kind kind,
        boolean hidden,
        Handler handler) {
    public Command {
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    @FunctionalInterface
    public interface Handler {
        void handle(AtomicBoolean cancelled, Ui ui) throws Exception;
    }
}
