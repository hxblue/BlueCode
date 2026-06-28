package com.bluecode.tui;

import com.bluecode.config.ProviderConfig;
import com.bluecode.agent.SessionRuntime;
import com.bluecode.permission.PermissionEngine;
import com.bluecode.tool.Registry;
import com.bluecode.tui.tea.Command;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class bluecodeModelTest {
    @TempDir
    Path tempDir;

    @Test
    void dispatchHelpListsBuiltinsAndNonSlashReturnsNull() {
        bluecodeModel app = app();

        Command notSlash = app.dispatchSlash("hello");
        Command help = app.dispatchSlash("/Help");

        assertNull(notSlash);
        assertInstanceOf(Command.None.class, help);
        String output = app.chatMessages().getLast().content();
        assertTrue(output.contains("/clear"));
        assertTrue(output.contains("/status"));
    }

    @Test
    void dispatchUnknownSlashIsFriendly() {
        bluecodeModel app = app();

        app.dispatchSlash("/foobar");

        String output = app.chatMessages().getLast().content();
        assertTrue(output.contains("未知命令"));
        assertTrue(output.contains("/help"));
    }

    @Test
    void dispatchPlanChangesModeWithoutAddingUserMessage() {
        bluecodeModel app = app();

        app.dispatchSlash("/plan");

        assertTrue(app.chatMessages().getLast().content().contains("PLAN"));
        assertTrue(app.dumpHistory().contains("System"));
        assertTrue(app.dumpHistory().contains("PLAN"));
    }

    @Test
    void loadsProjectSkillsIntoHelpAndSkillsList() throws Exception {
        Path skillDir = tempDir.resolve(".bluecode/skills/demo");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: demo
                description: demo skill
                ---
                Demo SOP
                """);
        bluecodeModel app = new bluecodeModel(
                List.of(provider()),
                Registry.createDefault(),
                PermissionEngine.create(tempDir),
                SessionRuntime.create(tempDir, 200000),
                null,
                null,
                "",
                "",
                tempDir);

        app.dispatchSlash("/help");
        String help = app.chatMessages().getLast().content();
        app.dispatchSlash("/skills");
        String skills = app.chatMessages().getLast().content();

        assertTrue(help.contains("/demo"));
        assertTrue(skills.contains("/demo"));
        assertTrue(app.listCatalogSkills().contains("demo"));
    }

    private bluecodeModel app() {
        return new bluecodeModel(
                List.of(provider()),
                Registry.createDefault(),
                PermissionEngine.create(tempDir));
    }

    private ProviderConfig provider() {
        ProviderConfig provider = new ProviderConfig();
        provider.setName("test");
        provider.setProtocol("openai-compat");
        provider.setBaseUrl("http://127.0.0.1:65535/v1");
        provider.setApiKey("test-key");
        provider.setModel("test-model");
        return provider;
    }
}
