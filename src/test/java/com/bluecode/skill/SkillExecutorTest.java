package com.bluecode.skill;

import com.bluecode.conversation.Message;
import com.bluecode.tool.Registry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillExecutorTest {
    @Test
    void substituteArgumentsReplacesPlaceholderOrAppendsUserRequest() {
        assertEquals("Do this: hello", SkillExecutor.substituteArguments("Do this: $ARGUMENTS", "hello"));

        String appended = SkillExecutor.substituteArguments("Follow SOP", "please review");
        assertTrue(appended.contains("Follow SOP"));
        assertTrue(appended.contains("## User Request"));
        assertTrue(appended.contains("please review"));

        assertEquals("Follow SOP", SkillExecutor.substituteArguments("Follow SOP", " "));
    }

    @Test
    void executeInlineValidatesToolsActivatesAndFilters() {
        Host host = new Host();
        SkillCatalog.Skill skill = skill(List.of("ReadFile"), "Use $ARGUMENTS");

        String rendered = SkillExecutor.executeInline(skill, "docs", host);

        assertEquals("Use docs", rendered);
        assertEquals("demo", host.activeName);
        assertEquals("Use docs", host.activeBody);
        assertTrue(host.filter.test("ReadFile"));
        assertTrue(!host.filter.test("WriteFile"));
    }

    @Test
    void missingAllowedToolThrowsBeforeActivation() {
        Host host = new Host();
        SkillCatalog.Skill skill = skill(List.of("NoSuchTool"), "body");

        assertThrows(IllegalStateException.class, () -> SkillExecutor.executeInline(skill, "", host));
    }

    @Test
    void buildForkSeedSupportsNoneRecentAndFull() {
        List<Message> parent = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            parent.add(new Message(Message.Role.USER, "m" + i));
        }

        assertEquals(0, SkillExecutor.buildForkSeed("none", parent).size());
        assertEquals(5, SkillExecutor.buildForkSeed("recent", parent).size());
        assertEquals("m2", SkillExecutor.buildForkSeed("recent", parent).getFirst().content());
        assertEquals(7, SkillExecutor.buildForkSeed("full", parent).size());
    }

    private SkillCatalog.Skill skill(List<String> allowedTools, String body) {
        return new SkillCatalog.Skill(new SkillCatalog.SkillMeta(
                "demo",
                "desc",
                "",
                List.of(),
                allowedTools,
                "inline",
                "",
                "none"), body, null, true);
    }

    private static final class Host implements SkillHost {
        private final Registry registry = Registry.createDefault();
        private Predicate<String> filter = ignored -> true;
        private String activeName;
        private String activeBody;

        @Override
        public void activateSkill(String name, String body) {
            activeName = name;
            activeBody = body;
        }

        @Override
        public void setToolFilter(Predicate<String> filter) {
            this.filter = filter;
        }

        @Override
        public Registry toolRegistry() {
            return registry;
        }
    }
}
