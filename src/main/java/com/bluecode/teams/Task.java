package com.bluecode.teams;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record Task(
        @JsonProperty("id") String id,
        @JsonProperty("title") String title,
        @JsonProperty("description") String description,
        @JsonProperty("status") Status status,
        @JsonProperty("assignee") String assignee,
        @JsonProperty("blockedBy") List<String> blockedBy,
        @JsonProperty("blocks") List<String> blocks,
        @JsonProperty("createdAt") long createdAt,
        @JsonProperty("updatedAt") long updatedAt) {
    public Task {
        id = id == null ? "" : id;
        title = title == null ? "" : title;
        description = description == null ? "" : description;
        status = status == null ? Status.PENDING : status;
        assignee = assignee == null ? "" : assignee;
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
