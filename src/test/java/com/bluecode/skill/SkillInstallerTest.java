package com.bluecode.skill;

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SkillInstallerTest {
    @Test
    void parsesGithubTreeUrl() {
        SkillInstaller.SkillUrl url = SkillInstaller.parseSkillURL(
                "https://github.com/acme/blue/tree/main/skills/demo");

        assertEquals("acme", url.owner());
        assertEquals("blue", url.repo());
        assertEquals("main", url.ref());
        assertEquals("skills/demo", url.path());
        assertEquals("demo", url.skillName());
    }

    @Test
    void parsesRawGithubSkillFileAsParentDirectory() {
        SkillInstaller.SkillUrl url = SkillInstaller.parseSkillURL(
                "https://raw.githubusercontent.com/acme/blue/main/skills/demo/SKILL.md");

        assertEquals("skills/demo", url.path());
        assertEquals("demo", url.skillName());
    }

    @Test
    void parsesSkillsShUrlQuery() {
        String nested = URLEncoder.encode(
                "https://github.com/acme/blue/tree/main/skills/demo",
                StandardCharsets.UTF_8);

        SkillInstaller.SkillUrl url = SkillInstaller.parseSkillURL("https://skills.sh/install?url=" + nested);

        assertEquals("acme", url.owner());
        assertEquals("blue", url.repo());
        assertEquals("skills/demo", url.path());
    }
}
