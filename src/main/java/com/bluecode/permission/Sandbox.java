package com.bluecode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

public final class Sandbox {
    private Sandbox() {
    }

    static Path resolveRoot(Path root) throws IOException {
        return root.toAbsolutePath().normalize().toRealPath();
    }

    static boolean sandboxOK(Path root, String path) {
        try {
            Path raw = path == null || path.isBlank() ? Path.of(".") : Path.of(path);
            Path absolute = raw.isAbsolute() ? raw : root.resolve(raw);
            Path resolved = evalSymlinksOrAncestor(absolute);
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path normalizedResolved = resolved.toAbsolutePath().normalize();
            return normalizedResolved.equals(normalizedRoot) || normalizedResolved.startsWith(normalizedRoot);
        } catch (IOException | InvalidPathException e) {
            return false;
        }
    }

    static Path evalSymlinksOrAncestor(Path absolute) throws IOException {
        Path target = absolute.toAbsolutePath().normalize();
        if (Files.exists(target)) {
            return target.toRealPath();
        }

        Deque<Path> missingTail = new ArrayDeque<>();
        Path cursor = target;
        while (cursor != null && !Files.exists(cursor)) {
            Path fileName = cursor.getFileName();
            if (fileName != null) {
                missingTail.addFirst(fileName);
            }
            cursor = cursor.getParent();
        }

        if (cursor == null) {
            return target;
        }

        Path resolved = cursor.toRealPath();
        for (Path part : missingTail) {
            resolved = resolved.resolve(part.toString());
        }
        return resolved.normalize();
    }
}
