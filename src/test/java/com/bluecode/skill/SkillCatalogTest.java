package com.bluecode.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillCatalogTest {
    @TempDir
    Path tempDir;

    @Test
    void loadCatalogProjectOverridesUserSkill() throws Exception {
        String oldHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home");
        Path project = tempDir.resolve("project");
        System.setProperty("user.home", home.toString());
        try {
            writeSkill(home.resolve(".bluecode/skills/demo"), "demo", "user desc", "user body");
            writeSkill(project.resolve(".bluecode/skills/demo"), "demo", "project desc", "project body");

            SkillCatalog catalog = new SkillCatalog().loadCatalog(project);

            assertEquals(1, catalog.list().size());
            SkillCatalog.Skill skill = catalog.getFull("demo").orElseThrow();
            assertEquals("project desc", skill.meta().description());
            assertTrue(skill.promptBody().contains("project body"));
        } finally {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void yamlAndPromptTakePriorityOverSkillMd() throws Exception {
        Path root = tempDir.resolve("skills");
        Path dir = root.resolve("demo");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: demo
                description: markdown desc
                ---
                markdown body
                """);
        Files.writeString(dir.resolve("skill.yaml"), """
                name: demo
                description: yaml desc
                allowed_tools:
                  - ReadFile
                """);
        Files.writeString(dir.resolve("prompt.md"), "yaml prompt body");

        SkillCatalog catalog = new SkillCatalog().loadFromDirectory(root);

        SkillCatalog.Skill skill = catalog.getFull("demo").orElseThrow();
        assertEquals("yaml desc", skill.meta().description());
        assertEquals("yaml prompt body", skill.promptBody());
        assertEquals("ReadFile", skill.meta().allowedTools().getFirst());
    }

    @Test
    void getFullReloadsBodyFromDisk() throws Exception {
        Path root = tempDir.resolve("skills");
        Path dir = root.resolve("demo");
        writeSkill(dir, "demo", "desc", "first body");
        SkillCatalog catalog = new SkillCatalog().loadFromDirectory(root);

        assertTrue(catalog.getFull("demo").orElseThrow().promptBody().contains("first body"));

        writeSkill(dir, "demo", "desc", "second body");

        assertTrue(catalog.getFull("demo").orElseThrow().promptBody().contains("second body"));
    }

    @Test
    void buildActiveContextRendersRequestedSkillsOnly() throws Exception {
        Path root = tempDir.resolve("skills");
        writeSkill(root.resolve("demo"), "demo", "desc", "demo body");
        writeSkill(root.resolve("other"), "other", "desc", "other body");
        SkillCatalog catalog = new SkillCatalog().loadFromDirectory(root);

        String context = catalog.buildActiveContext(Set.of("demo"));

        assertTrue(context.contains("## Active Skills"));
        assertTrue(context.contains("demo body"));
        assertTrue(!context.contains("other body"));
    }

    private void writeSkill(Path dir, String name, String description, String body) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), """
                ---
                name: %s
                description: %s
                ---
                %s
                """.formatted(name, description, body));
    }
}
