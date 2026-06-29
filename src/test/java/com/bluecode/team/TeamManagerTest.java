package com.bluecode.team;

import com.bluecode.teams.AgentNameRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void createSanitizesAndSuffixesDuplicateNames() throws Exception {
        TeamManager manager = manager();

        Team first = manager.create("foo bar/baz", "");
        Team second = manager.create("foo bar/baz", "");

        assertEquals("foo-bar-baz", first.sanitizedName());
        assertEquals("foo-bar-baz-2", second.sanitizedName());
        assertTrue(Files.exists(first.configPath()));
        assertTrue(Files.exists(second.configPath()));
        assertTrue(manager.get("foo bar/baz").isPresent());
    }

    @Test
    void memberMutationReloadsMembersFromDiskBeforeSaving() throws Exception {
        TeamManager manager = manager();
        Team team = manager.create("demo", "");
        TeammateInfo alice = new TeammateInfo(
                "alice", "agent-1", "general-purpose", "", "", "",
                BackendType.IN_PROCESS, "", true, false, "");
        Persistence.atomicWriteJson(team.configPath(), new Team.Snapshot(
                team.name(),
                team.sanitizedName(),
                team.description(),
                team.leadAgentId(),
                team.backend(),
                team.createdAt(),
                List.of(team.members().getFirst(), alice)));

        assertTrue(team.setMemberActive("alice", false));

        Team.Snapshot snapshot = Persistence.readJson(team.configPath(), Team.Snapshot.class).orElseThrow();
        TeammateInfo saved = snapshot.members().stream()
                .filter(member -> member.name().equals("alice"))
                .findFirst()
                .orElseThrow();
        assertFalse(saved.isActive());
    }

    private TeamManager manager() throws Exception {
        return new TeamManager(tempDir, tempDir.resolve("repo"), null, new com.bluecode.task.Manager(), new AgentNameRegistry());
    }
}
