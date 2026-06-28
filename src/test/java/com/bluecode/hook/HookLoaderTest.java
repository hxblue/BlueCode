package com.bluecode.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsProjectAndUserHooksAndSkipsDuplicateNames() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        write(tempDir.resolve(".bluecode/hooks.yaml"), """
                hooks:
                  - name: project-start
                    event: SessionStart
                    action: { type: prompt, text: "项目" }
                """);
        write(home.resolve(".bluecode/hooks.yaml"), """
                hooks:
                  - name: project-start
                    event: Stop
                    action: { type: prompt, text: "重复" }
                  - name: user-stop
                    event: Stop
                    action: { type: prompt, text: "用户" }
                """);

        String err = withUserHome(home, () -> {
            HookEngine engine = HookLoader.load(tempDir);
            assertEquals(2, engine.rules().size());
            assertEquals(2, engine.sources().size());
        });

        assertTrue(err.contains("duplicate name"));
    }

    @Test
    void invalidHookIsReportedAndSkipped() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        write(tempDir.resolve(".bluecode/hooks.yaml"), """
                hooks:
                  - name: bad-async
                    event: PreToolUse
                    async: true
                    action: { type: shell, command: "echo x" }
                  - name: bad-condition
                    event: SessionStart
                    if:
                      all_of: []
                      any_of: []
                    action: { type: prompt, text: "x" }
                  - name: good-hook
                    event: SessionStart
                    action: { type: prompt, text: "ok" }
                """);

        String err = withUserHome(home, () -> {
            HookEngine engine = HookLoader.load(tempDir);
            assertEquals(1, engine.rules().size());
            assertEquals("good-hook", engine.rules().getFirst().name());
        });

        assertTrue(err.contains("async not allowed for blocking events"));
        assertTrue(err.contains("cannot contain both all_of and any_of"));
    }

    private void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
    }

    private String withUserHome(Path home, ThrowingRunnable runnable) throws Exception {
        String oldHome = System.getProperty("user.home");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream oldErr = System.err;
        System.setProperty("user.home", home.toString());
        System.setErr(new PrintStream(buffer));
        try {
            runnable.run();
        } finally {
            System.setErr(oldErr);
            if (oldHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", oldHome);
            }
        }
        return buffer.toString();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
