package com.bluecode.tui.tea;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public sealed interface Command permits Command.None, Command.Tick, Command.CheckWindowSize, Command.Batch, Command.PrintLine, Command.Quit {
    static Command none() {
        return new None();
    }

    static Command tick(Duration delay, Function<Instant, Message> fn) {
        return new Tick(delay, fn);
    }

    static Command checkWindowSize() {
        return new CheckWindowSize();
    }

    static Command batch(Command... commands) {
        return new Batch(Arrays.stream(commands).filter(command -> !(command instanceof None)).toList());
    }

    static Command println(String text) {
        return new PrintLine(text);
    }

    static Command quit() {
        return new Quit();
    }

    record None() implements Command {
    }

    record Tick(Duration delay, Function<Instant, Message> fn) implements Command {
    }

    record CheckWindowSize() implements Command {
    }

    record Batch(List<Command> commands) implements Command {
    }

    record PrintLine(String text) implements Command {
    }

    record Quit() implements Command {
    }
}
