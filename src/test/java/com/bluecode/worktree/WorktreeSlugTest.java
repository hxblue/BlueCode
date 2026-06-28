package com.bluecode.worktree;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorktreeSlugTest {
    @Test
    void acceptsSafeNamesAndFlattensNestedNames() {
        assertDoesNotThrow(() -> WorktreeSlug.validate("alice"));
        assertDoesNotThrow(() -> WorktreeSlug.validate("team/alice"));
        assertDoesNotThrow(() -> WorktreeSlug.validate("v1.0_a-b"));

        assertEquals("team+alice", WorktreeSlug.flatten("team/alice"));
    }

    @Test
    void rejectsTraversalAndUnsafeSegments() {
        for (String value : new String[]{"", "..", "./x", "a//b", "/x", "a/", "a b", "a;b"}) {
            assertThrows(IllegalArgumentException.class, () -> WorktreeSlug.validate(value), value);
        }
        assertThrows(IllegalArgumentException.class, () -> WorktreeSlug.validate("a".repeat(65)));
    }
}
