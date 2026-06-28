package com.bluecode.subagent;

import com.bluecode.permission.Mode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {
    @Test
    void parsesDefinitionAndDontAsk() throws Exception {
        Definition definition = Parser.parseDefinition("""
                ---
                name: auto-bash
                description: 自动运行命令
                model: opus
                maxTurns: 5
                permissionMode: dontAsk
                background: true
                disallowedTools:
                  - WriteFile
                ---

                直接完成任务。
                """.getBytes(StandardCharsets.UTF_8), "memory:auto-bash.md", Definition.Source.PROJECT);

        assertEquals("auto-bash", definition.name());
        assertEquals("opus", definition.model());
        assertEquals(5, definition.maxTurns());
        assertEquals(Mode.DEFAULT, definition.permissionMode());
        assertTrue(definition.dontAsk());
        assertTrue(definition.background());
        assertEquals("WriteFile", definition.disallowedTools().getFirst());
        assertTrue(definition.systemPrompt().contains("直接完成任务"));
    }

    @Test
    void missingRequiredFieldsFail() {
        assertThrows(Parser.ParserException.class, () -> Parser.parseDefinition("""
                ---
                description: missing name
                ---
                body
                """.getBytes(StandardCharsets.UTF_8), "bad.md", Definition.Source.USER));
    }

    @Test
    void invalidModelFallsBackToInherit() throws Exception {
        Definition definition = Parser.parseDefinition("""
                ---
                name: bad
                description: bad model
                model: gpt-4
                permissionMode: weird
                ---
                body
                """.getBytes(StandardCharsets.UTF_8), "bad.md", Definition.Source.USER);

        assertEquals("inherit", definition.model());
        assertEquals(Mode.DEFAULT, definition.permissionMode());
    }

    @Test
    void parsesIsolationWorktreeAndFallsBackForInvalidValue() throws Exception {
        Definition isolated = Parser.parseDefinition("""
                ---
                name: worker
                description: worktree worker
                isolation: worktree
                ---
                body
                """.getBytes(StandardCharsets.UTF_8), "worker.md", Definition.Source.PROJECT);
        Definition invalid = Parser.parseDefinition("""
                ---
                name: invalid
                description: invalid isolation
                isolation: sandbox
                ---
                body
                """.getBytes(StandardCharsets.UTF_8), "invalid.md", Definition.Source.PROJECT);

        assertEquals("worktree", isolated.isolation());
        assertEquals("", invalid.isolation());
    }
}
