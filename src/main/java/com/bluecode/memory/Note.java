package com.bluecode.memory;

import java.time.Instant;

public record Note(
        NoteType type,
        String title,
        String slug,
        String content,
        String filename,
        Instant created,
        Instant updated) {
}
