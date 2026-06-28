package com.bluecode.permission;

import com.bluecode.llm.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PermissionEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void parsesModes() {
        assertEquals(Mode.DEFAULT, Mode.parse("default").orElseThrow());
        assertEquals(Mode.ACCEPT_EDITS, Mode.parse("acceptEdits").orElseThrow());
        assertEquals(Mode.PLAN, Mode.parse("PLAN").orElseThrow());
        assertEquals(Mode.BYPASS, Mode.parse("bypassPermissions").orElseThrow());
        assertTrue(Mode.parse("x").isEmpty());
    }

    @Test
    void blacklistBlocksDangerousCommandsEvenInBypass() {
        PermissionEngine engine = PermissionEngine.create(tempDir);

        PermissionEngine.CheckResult rm = engine.check(Mode.BYPASS,
                bash("rm -rf /"), false);
        PermissionEngine.CheckResult fork = engine.check(Mode.BYPASS,
                bash(":(){ :|:& };:"), false);

        assertEquals(Decision.DENY, rm.decision());
        assertEquals(Decision.DENY, fork.decision());
        assertEquals(Decision.ALLOW, engine.check(Mode.BYPASS, bash("git status"), false).decision());
    }

    @Test
    void sandboxAllowsProjectPathsAndBlocksOutsidePaths() throws IOException {
        PermissionEngine engine = PermissionEngine.create(tempDir);
        Path insideNewFile = tempDir.resolve("a/b/c.txt");
        Path outside = Files.createTempFile("bluecode-outside", ".txt");

        assertEquals(Decision.ALLOW, engine.check(Mode.BYPASS,
                write(insideNewFile, "ok"), false).decision());
        assertEquals(Decision.DENY, engine.check(Mode.BYPASS,
                read(outside), true).decision());
        assertEquals(Decision.DENY, engine.check(Mode.BYPASS,
                read(Path.of("..").resolve(outside.getFileName())), true).decision());
    }

    @Test
    void sandboxResolvesSymlinksBeforePrefixCheck() throws IOException {
        Path outsideDir = Files.createTempDirectory("bluecode-outside-link");
        Path link = tempDir.resolve("link-out");
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "当前环境不支持创建符号链接");
        }
        PermissionEngine engine = PermissionEngine.create(tempDir);

        assertEquals(Decision.DENY, engine.check(Mode.BYPASS,
                read(link.resolve("secret.txt")), true).decision());
    }

    @Test
    void rulesSupportExactGlobAndDenyPriority() {
        RuleSet rules = new RuleSet(
                List.of(
                        Rule.parse("Bash(git *)", true).orElseThrow(),
                        Rule.parse("Write(src/**)", true).orElseThrow()),
                List.of(Rule.parse("Bash(git push)", false).orElseThrow()));

        assertEquals(Decision.ALLOW, rules.match("Bash", "git status").orElseThrow());
        assertEquals(Decision.DENY, rules.match("Bash", "git push").orElseThrow());
        assertEquals(Decision.ALLOW, rules.match("Write", "src/a/b.java").orElseThrow());
        assertTrue(rules.match("Write", "docs/a.md").isEmpty());
    }

    @Test
    void rulesSupportExactRegexAndNotSyntax() {
        RuleSet exact = new RuleSet(List.of(Rule.parse("Bash(=git status)", true).orElseThrow()), List.of());
        assertEquals(Decision.ALLOW, exact.match("Bash", "git status").orElseThrow());
        assertTrue(exact.match("Bash", "git status -s").isEmpty());

        RuleSet regex = new RuleSet(List.of(Rule.parse("Bash(~^npm (install|test)$)", true).orElseThrow()), List.of());
        assertEquals(Decision.ALLOW, regex.match("Bash", "npm install").orElseThrow());
        assertTrue(regex.match("Bash", "npm run dev").isEmpty());

        RuleSet not = new RuleSet(List.of(Rule.parse("Bash(!~^rm)", true).orElseThrow()), List.of());
        assertEquals(Decision.ALLOW, not.match("Bash", "ls -lh").orElseThrow());
        assertTrue(not.match("Bash", "rm -rf .").isEmpty());
    }

    @Test
    void invalidRulesAreLoggedAndSkipped() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream oldErr = System.err;
        System.setErr(new PrintStream(buffer));
        RuleSet rules;
        try {
            rules = Settings.toRuleSet(new Settings(null,
                    List.of("Bash(~[invalid)", "Bash(git *)"),
                    List.of()));
        } finally {
            System.setErr(oldErr);
        }

        assertTrue(buffer.toString().contains("parse failed"));
        assertEquals(Decision.ALLOW, rules.match("Bash", "git status").orElseThrow());
    }

    @Test
    void configurationLayersAndDefaultModeUseNearestFile() throws IOException {
        withUserHome(Files.createDirectories(tempDir.resolve("home")), () -> {
            writeYaml(tempDir.resolve("home/.bluecode/settings.yaml"), """
                    defaultMode: bypassPermissions
                    permissions:
                      allow:
                        - Bash(git push)
                    """);
            writeYaml(tempDir.resolve(".bluecode/settings.yaml"), """
                    defaultMode: plan
                    permissions:
                      allow:
                        - Bash(git *)
                    """);
            writeYaml(tempDir.resolve(".bluecode/settings.local.yaml"), """
                    defaultMode: acceptEdits
                    permissions:
                      deny:
                        - Bash(git push)
                    """);

            PermissionEngine engine = PermissionEngine.create(tempDir);

            assertEquals(Mode.ACCEPT_EDITS, engine.startMode());
            assertEquals(Decision.ALLOW, engine.check(Mode.DEFAULT, bash("git status"), false).decision());
            assertEquals(Decision.DENY, engine.check(Mode.DEFAULT, bash("git push"), false).decision());
        });
    }

    @Test
    void invalidConfigurationDowngradesToEmptyRules() throws IOException {
        withUserHome(Files.createDirectories(tempDir.resolve("home")), () -> {
            writeYaml(tempDir.resolve(".bluecode/settings.yaml"), "permissions: [");
            PermissionEngine engine = PermissionEngine.create(tempDir);

            PermissionEngine.CheckResult result = engine.check(Mode.DEFAULT,
                    write(tempDir.resolve("new.txt"), "x"), false);

            assertEquals(Decision.ASK, result.decision());
        });
    }

    @Test
    void fileArgumentParseFailureIsDenied() {
        PermissionEngine engine = PermissionEngine.create(tempDir);

        PermissionEngine.CheckResult result = engine.check(Mode.BYPASS,
                new ToolCall("bad", "WriteFile", "not-json"), false);

        assertEquals(Decision.DENY, result.decision());
    }

    @Test
    void permanentAllowWritesLocalSettingsAndReloads() throws IOException {
        withUserHome(Files.createDirectories(tempDir.resolve("home")), () -> {
            PermissionEngine engine = PermissionEngine.create(tempDir);
            ToolCall call = write(tempDir.resolve("notes/a.txt"), "hello");

            assertEquals(Decision.ASK, engine.check(Mode.DEFAULT, call, false).decision());
            engine.persistLocalAllow(call);

            Path local = tempDir.resolve(".bluecode/settings.local.yaml");
            assertTrue(Files.readString(local).contains("Write(notes/a.txt)"));
            PermissionEngine reloaded = PermissionEngine.create(tempDir);
            assertEquals(Decision.ALLOW, reloaded.check(Mode.DEFAULT, call, false).decision());
        });
    }

    private ToolCall bash(String command) {
        return new ToolCall("bash", "Bash", "{\"command\":\"" + json(command) + "\"}");
    }

    private ToolCall read(Path path) {
        return new ToolCall("read", "ReadFile", "{\"path\":\"" + json(path.toString()) + "\"}");
    }

    private ToolCall write(Path path, String content) {
        return new ToolCall("write", "WriteFile",
                "{\"path\":\"" + json(path.toString()) + "\",\"content\":\"" + json(content) + "\"}");
    }

    private void writeYaml(Path path, String yaml) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, yaml);
    }

    private void withUserHome(Path home, ThrowingRunnable body) throws IOException {
        String old = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            body.run();
        } finally {
            if (old == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", old);
            }
        }
    }

    private String json(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws IOException;
    }
}
