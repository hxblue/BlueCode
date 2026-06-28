package com.bluecode.subagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsBuiltinDefinitions() {
        assertEquals(3, BuiltinLoader.builtinDefinitions().size());
        Catalog catalog = Catalog.load(tempDir);

        assertTrue(catalog.resolve("general-purpose").isPresent());
        assertTrue(catalog.resolve("Explore").isPresent());
        assertTrue(catalog.resolve("Plan").isPresent());
    }

    @Test
    void projectDefinitionOverridesBuiltin() throws Exception {
        Path agents = tempDir.resolve(".bluecode").resolve("agents");
        Files.createDirectories(agents);
        Files.writeString(agents.resolve("explore.md"), """
                ---
                name: Explore
                description: project override
                maxTurns: 7
                ---
                project body
                """);

        Definition definition = Catalog.load(tempDir).resolve("Explore").orElseThrow();

        assertEquals(Definition.Source.PROJECT, definition.source());
        assertEquals(7, definition.maxTurns());
        assertTrue(definition.systemPrompt().contains("project body"));
    }

    @Test
    void forkDefinitionIsMarked() {
        assertTrue(Catalog.load(tempDir).forkDefinition().isFork());
    }
}
