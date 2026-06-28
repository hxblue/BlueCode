package com.bluecode.command;

import com.bluecode.permission.Mode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorktreeCommandTest {
    @Test
    void createListEnterExitAndRemoveDispatchToAccessor() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        RecordingUi ui = new RecordingUi();

        ui.args = "create demo";
        registry.lookup("worktree").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        ui.args = "list";
        registry.lookup("worktree").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        ui.args = "enter demo";
        registry.lookup("worktree").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        ui.args = "exit --remove --discard";
        registry.lookup("worktree").orElseThrow().handler().handle(new AtomicBoolean(false), ui);
        ui.args = "remove demo --discard";
        registry.lookup("worktree").orElseThrow().handler().handle(new AtomicBoolean(false), ui);

        assertEquals(List.of("create:demo", "list", "enter:demo", "exit:true:true", "remove:demo:true"),
                ui.accessor.calls);
        assertTrue(ui.printlns.stream().anyMatch(line -> line.contains("Worktree 已创建")));
        assertTrue(ui.printlns.stream().anyMatch(line -> line.contains("demo")));
    }

    @Test
    void missingAccessorReportsError() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        Builtins.registerAll(registry);
        RecordingUi ui = new RecordingUi();
        ui.accessor = null;
        ui.args = "list";

        registry.lookup("worktree").orElseThrow().handler().handle(new AtomicBoolean(false), ui);

        assertEquals(List.of("Worktree 功能未启用"), ui.errors);
    }

    private static final class RecordingUi implements Ui {
        private final List<String> printlns = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private StubAccessor accessor = new StubAccessor();
        private String args = "";

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
            return Mode.DEFAULT;
        }

        @Override
        public void setMode(Mode mode) {
        }

        @Override
        public void injectAndSend(String displayLabel, String presetPrompt) {
        }

        @Override
        public long usageIn() {
            return 0;
        }

        @Override
        public long usageOut() {
            return 0;
        }

        @Override
        public String modelName() {
            return "";
        }

        @Override
        public String cwd() {
            return "";
        }

        @Override
        public int toolCount() {
            return 0;
        }

        @Override
        public List<String> memoryFiles() {
            return List.of();
        }

        @Override
        public List<com.bluecode.hook.HookRule> hookRules() {
            return List.of();
        }

        @Override
        public List<String> hookSources() {
            return List.of();
        }

        @Override
        public String sessionPath() {
            return "";
        }

        @Override
        public String sessionId() {
            return "";
        }

        @Override
        public void quit() {
        }

        @Override
        public void forceCompact() {
        }

        @Override
        public void openResumeMenu() {
        }

        @Override
        public void clearAndNewSession() {
        }

        @Override
        public boolean idle() {
            return true;
        }

        @Override
        public String commandArguments() {
            return args;
        }

        @Override
        public WorktreeAccessor worktreeAccessor() {
            return accessor;
        }
    }

    private static final class StubAccessor implements WorktreeAccessor {
        private final List<String> calls = new ArrayList<>();

        @Override
        public CreateResult create(String name) {
            calls.add("create:" + name);
            return new CreateResult("/tmp/" + name, "worktree-" + name);
        }

        @Override
        public List<WorktreeSummary> list() {
            calls.add("list");
            return List.of(new WorktreeSummary("demo", "/tmp/demo", "worktree-demo", false, true));
        }

        @Override
        public void enter(String name) {
            calls.add("enter:" + name);
        }

        @Override
        public ExitResult exit(boolean remove, boolean discard) {
            calls.add("exit:" + remove + ":" + discard);
            return new ExitResult(remove, "/tmp/demo", "worktree-demo");
        }

        @Override
        public void remove(String name, boolean discard) throws IOException {
            calls.add("remove:" + name + ":" + discard);
        }
    }
}
