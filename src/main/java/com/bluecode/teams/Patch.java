package com.bluecode.teams;

import java.util.List;
import java.util.Optional;

public record Patch(
        Optional<String> title,
        Optional<String> description,
        Optional<Status> status,
        Optional<String> assignee,
        List<String> addBlocks,
        List<String> addBlockedBy,
        List<String> removeBlocks,
        List<String> removeBlockedBy) {
    public Patch {
        title = title == null ? Optional.empty() : title;
        description = description == null ? Optional.empty() : description;
        status = status == null ? Optional.empty() : status;
        assignee = assignee == null ? Optional.empty() : assignee;
        addBlocks = addBlocks == null ? List.of() : List.copyOf(addBlocks);
        addBlockedBy = addBlockedBy == null ? List.of() : List.copyOf(addBlockedBy);
        removeBlocks = removeBlocks == null ? List.of() : List.copyOf(removeBlocks);
        removeBlockedBy = removeBlockedBy == null ? List.of() : List.copyOf(removeBlockedBy);
    }
}
