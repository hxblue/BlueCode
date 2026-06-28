package com.bluecode.command;

import java.io.IOException;
import java.util.List;

public interface WorktreeAccessor {
    CreateResult create(String name) throws IOException;

    List<WorktreeSummary> list();

    void enter(String name) throws IOException;

    ExitResult exit(boolean remove, boolean discard) throws IOException;

    void remove(String name, boolean discard) throws IOException;

    record CreateResult(String path, String branch) {
    }

    record ExitResult(boolean removed, String path, String branch) {
    }
}
