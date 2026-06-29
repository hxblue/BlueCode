package com.bluecode.teams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {
    @TempDir
    Path tempDir;

    @Test
    void createUpdateAndMaintainBlockedByEdges() throws Exception {
        Store store = new Store(tempDir.resolve("tasks.json"));
        String first = store.create(new Task("", "first", "", Status.PENDING, "", List.of(), List.of(), 0, 0));
        String second = store.create(new Task("", "second", "", Status.PENDING, "", List.of(), List.of(), 0, 0));

        assertTrue(first.matches("task_[0-9a-f]{6}"));
        store.update(second, new Patch(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(first),
                List.of(),
                List.of()));

        Task secondTask = store.get(second).orElseThrow();
        Task firstTask = store.get(first).orElseThrow();
        assertEquals(List.of(first), secondTask.blockedBy());
        assertEquals(List.of(second), firstTask.blocks());
        assertFalse(store.list(new Filter(Optional.of(Status.PENDING))).stream()
                .filter(view -> view.task().id().equals(second))
                .findFirst()
                .orElseThrow()
                .isReady());

        store.update(first, new Patch(Optional.empty(), Optional.empty(), Optional.of(Status.COMPLETED),
                Optional.empty(), List.of(), List.of(), List.of(), List.of()));
        assertTrue(store.list(new Filter(Optional.empty())).stream()
                .filter(view -> view.task().id().equals(second))
                .findFirst()
                .orElseThrow()
                .isReady());
    }
}
