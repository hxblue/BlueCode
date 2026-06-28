package com.bluecode.prompt;

import com.bluecode.tool.BashTool;
import com.bluecode.tool.EditFileTool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {
    @Test
    void buildsFixedModulesInPriorityOrder() {
        String prompt = PromptBuilder.buildSystemPrompt();

        assertTrue(prompt.indexOf("你是 BlueCode") < prompt.indexOf("读文件、找文件、搜内容"));
        assertTrue(prompt.contains("\n\n"));
        assertFalse(prompt.contains("\n\n\n"));
    }

    @Test
    void skipsEmptyOptionalModulesAndAllowsNewModuleByPriority() {
        String assembled = PromptBuilder.assembleSystem(List.of(
                new Module("late", 30, "late-content"),
                new Module("empty", 20, ""),
                new Module("early", 10, "early-content"),
                new Module("middle", 25, "middle-content")
        ));

        assertEquals("early-content\n\nmiddle-content\n\nlate-content", assembled);
        assertFalse(assembled.contains("empty"));
        assertFalse(assembled.contains("\n\n\n"));
    }

    @Test
    void stablePromptIsDeterministicAndDoesNotContainEnvironment() {
        String first = PromptBuilder.buildSystemPrompt();
        String second = PromptBuilder.buildSystemPrompt();

        assertEquals(first, second);
        assertFalse(first.contains("当前日期"));
        assertFalse(first.contains("Git 状态"));
        assertFalse(first.contains(PromptBuilder.currentWorkingDirectory()));
    }

    @Test
    void reinforcesImportantToolRulesInPromptAndToolDescriptions() {
        String prompt = PromptBuilder.buildSystemPrompt();

        assertTrue(prompt.contains("优先使用 ReadFile、Glob、Grep"));
        assertTrue(prompt.contains("编辑文件前必须先用 ReadFile"));
        assertTrue(new BashTool().description().contains("优先用 ReadFile/Glob/Grep"));
        assertTrue(new EditFileTool().description().contains("编辑前请先用 ReadFile"));
    }

    @Test
    void injectsInstructionsAndMemoryOnlyWhenPresent() {
        String prompt = PromptBuilder.buildSystemPrompt("项目指令", "长期记忆索引");
        String empty = PromptBuilder.buildSystemPrompt("", "");

        assertTrue(prompt.contains("项目指令"));
        assertTrue(prompt.contains("长期记忆索引"));
        assertFalse(empty.contains("项目指令"));
        assertFalse(empty.contains("长期记忆索引"));
    }
}
