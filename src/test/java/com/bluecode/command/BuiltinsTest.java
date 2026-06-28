package com.bluecode.command;

import com.bluecode.permission.Mode;
import com.bluecode.hook.Action;
import com.bluecode.hook.Event;
import com.bluecode.hook.HookRule;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinsTest {
    @Test
    void registerAllAllRegistered() {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);

        assertEquals(List.of(
                "clear",
                "compact",
                "do",
                "exit",
                "help",
                "hooks",
                "memory",
                "permission",
                "plan",
                "resume",
                "review",
                "session",
                "status",
                "worktree"), registry.visible().stream().map(Command::name).toList());
    }

    @Test
    void registerAllNoCollision() {
        CommandRegistry registry = new CommandRegistry();

        assertDoesNotThrow(() -> Builtins.registerAll(registry));
    }

    @Test
    void registerAllHandlersRunOnNopUi() {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);

        for (Command command : registry.visible()) {
            assertDoesNotThrow(() -> command.handler().handle(new AtomicBoolean(false), NopUi.INSTANCE), command.name());
        }
    }

    @Test
    void handleStatusPrintsAllKeys() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        RecordingUi ui = new RecordingUi();

        registry.lookup("status").orElseThrow().handler().handle(new AtomicBoolean(false), ui);

        assertEquals(1, ui.printlns.size());
        String output = ui.printlns.getFirst();
        assertTrue(output.contains("Mode:"));
        assertTrue(output.contains("Tokens:"));
        assertTrue(output.contains("Tools:"));
        assertTrue(output.contains("Memories:"));
        assertTrue(output.contains("Model:"));
        assertTrue(output.contains("Directory:"));
    }

    @Test
    void handleHooksPrintsLoadedRules() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        RecordingUi ui = new RecordingUi();
        ui.hookRules = List.of(new HookRule(
                "welcome",
                Event.SESSION_START,
                null,
                new Action.Prompt("hello"),
                true,
                true,
                Duration.ofSeconds(1),
                "hooks.yaml"));
        ui.hookSources = List.of("hooks.yaml");

        registry.lookup("hooks").orElseThrow().handler().handle(new AtomicBoolean(false), ui);

        String output = ui.printlns.getFirst();
        assertTrue(output.contains("SessionStart"));
        assertTrue(output.contains("welcome"));
        assertTrue(output.contains("[once] [async]"));
        assertTrue(output.contains("Loaded from: hooks.yaml"));
    }

    @Test
    void handleHooksPrintsEmptyState() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        RecordingUi ui = new RecordingUi();

        registry.lookup("hooks").orElseThrow().handler().handle(new AtomicBoolean(false), ui);

        assertEquals("No hooks loaded.", ui.printlns.getFirst());
    }

    @Test
    void handleCompactBlocksWhenBusy() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        RecordingUi ui = new RecordingUi();
        ui.idle = false;

        registry.lookup("compact").orElseThrow().handler().handle(new AtomicBoolean(false), ui);

        assertFalse(ui.forceCompactCalled);
        assertEquals(List.of("请等待当前任务完成"), ui.errors);
    }

    @Test
    void handleDoSetsModeAndInjects() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        RecordingUi ui = new RecordingUi();

        registry.lookup("do").orElseThrow().handler().handle(new AtomicBoolean(false), ui);

        assertEquals(Mode.DEFAULT, ui.mode);
        assertEquals("/do", ui.injectLabels.getFirst());
        assertTrue(ui.injectPrompts.getFirst().contains("请按上面的计划开始执行"));
    }

    private static final class RecordingUi implements Ui {
        private final List<String> printlns = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final List<String> injectLabels = new ArrayList<>();
        private final List<String> injectPrompts = new ArrayList<>();
        private List<HookRule> hookRules = List.of();
        private List<String> hookSources = List.of();
        private Mode mode = Mode.PLAN;
        private boolean idle = true;
        private boolean forceCompactCalled;

        @Override
        public void println(String msg) {
            printlns.add(msg);
        }

        @Override
        public void error(String msg) {
            errors.add(msg);
        }

        @Override
        public Mode mode() {
            return mode;
        }

        @Override
        public void setMode(Mode mode) {
            this.mode = mode;
        }

        @Override
        public void injectAndSend(String displayLabel, String presetPrompt) {
            injectLabels.add(displayLabel);
            injectPrompts.add(presetPrompt);
        }

        @Override
        public long usageIn() {
            return 3;
        }

        @Override
        public long usageOut() {
            return 5;
        }

        @Override
        public String modelName() {
            return "fake-model";
        }

        @Override
        public String cwd() {
            return "/tmp/project";
        }

        @Override
        public int toolCount() {
            return 6;
        }

        @Override
        public List<String> memoryFiles() {
            return List.of("MEMORY.md");
        }

        @Override
        public List<HookRule> hookRules() {
            return hookRules;
        }

        @Override
        public List<String> hookSources() {
            return hookSources;
        }

        @Override
        public String sessionPath() {
            return "/tmp/session";
        }

        @Override
        public String sessionId() {
            return "session-1";
        }

        @Override
        public void quit() {
        }

        @Override
        public void forceCompact() {
            forceCompactCalled = true;
        }

        @Override
        public void openResumeMenu() {
        }

        @Override
        public void clearAndNewSession() {
        }

        @Override
        public boolean idle() {
            return idle;
        }
    }
}
