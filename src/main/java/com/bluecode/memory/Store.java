package com.bluecode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public final class Store {
    private static final int MAX_INDEX_LINES = 200;
    private static final int MAX_INDEX_BYTES = 25 * 1024;

    private final Path dir;
    private final ReentrantLock lock = new ReentrantLock();

    public Store(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    public void ensureDir() throws IOException {
        Files.createDirectories(dir);
    }

    public String loadIndex() throws IOException {
        Path index = dir.resolve("MEMORY.md");
        if (!Files.isRegularFile(index)) {
            return "";
        }
        return Files.readString(index, StandardCharsets.UTF_8);
    }

    public void apply(List<UpdateAction> actions) throws IOException {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        lock.lock();
        try {
            ensureDir();
            for (UpdateAction action : actions) {
                applyOne(action);
            }
            rebuildIndex();
        } finally {
            lock.unlock();
        }
    }

    private void applyOne(UpdateAction action) throws IOException {
        if (action == null || action.action() == null) {
            return;
        }
        switch (action.action()) {
            case "create" -> create(action);
            case "update" -> update(action);
            case "delete" -> delete(action);
            default -> {
            }
        }
    }

    private void create(UpdateAction action) throws IOException {
        NoteType type = NoteType.fromWire(action.type());
        String slug = safeSlug(action.slug());
        if (slug.isBlank()) {
            slug = safeSlug(action.title());
        }
        if (slug.isBlank()) {
            slug = "note_" + Long.toHexString(System.nanoTime());
        }
        String filename = type.wire() + "_" + slug + ".md";
        Path file = safeFile(filename);
        writeNote(file, type.wire(), action.title(), action.content(), now(), now());
    }

    private void update(UpdateAction action) throws IOException {
        if (action.filename() == null || action.filename().isBlank()) {
            return;
        }
        Path file = safeFile(action.filename());
        String existing = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        String type = nonBlank(action.type(), frontmatterValue(existing, "type"), inferTypeFromFilename(file.getFileName().toString()));
        String created = nonBlank(frontmatterValue(existing, "created"), now());
        writeNote(file, type, action.title(), action.content(), created, now());
    }

    private void delete(UpdateAction action) throws IOException {
        if (action.filename() == null || action.filename().isBlank()) {
            return;
        }
        Files.deleteIfExists(safeFile(action.filename()));
    }

    private void rebuildIndex() throws IOException {
        List<String> lines = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .filter(path -> !"MEMORY.md".equals(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String type = nonBlank(frontmatterValue(text, "type"), inferTypeFromFilename(file.getFileName().toString()));
                String title = nonBlank(frontmatterValue(text, "title"), file.getFileName().toString());
                String summary = summarize(body(text));
                lines.add("- [%s] %s — %s".formatted(type, title, summary));
                if (lines.size() >= MAX_INDEX_LINES) {
                    break;
                }
            }
        }
        String index = trimBytes(String.join(System.lineSeparator(), lines), MAX_INDEX_BYTES);
        if (!index.isBlank()) {
            index += System.lineSeparator();
        }
        Files.writeString(
                dir.resolve("MEMORY.md"),
                index,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private void writeNote(Path file, String type, String title, String content, String created, String updated)
            throws IOException {
        String safeTitle = nonBlank(title, file.getFileName().toString().replace(".md", ""));
        String body = content == null ? "" : content.strip();
        String text = """
                ---
                type: %s
                title: %s
                created: %s
                updated: %s
                ---
                %s
                """.formatted(type, safeTitle, created, updated, body).stripTrailing() + System.lineSeparator();
        Files.writeString(
                file,
                text,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private Path safeFile(String filename) throws IOException {
        String safeName = filename.replace('\\', '/');
        if (safeName.contains("/") || safeName.contains("..")) {
            throw new IOException("非法记忆文件名: " + filename);
        }
        if (!safeName.endsWith(".md")) {
            safeName += ".md";
        }
        Path file = dir.resolve(safeName).normalize();
        if (!file.startsWith(dir)) {
            throw new IOException("记忆文件越界: " + filename);
        }
        return file;
    }

    private static String frontmatterValue(String text, String key) {
        if (text == null || !text.startsWith("---")) {
            return "";
        }
        String[] lines = text.split("\\R");
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i])) {
                break;
            }
            String prefix = key + ":";
            if (lines[i].startsWith(prefix)) {
                return lines[i].substring(prefix.length()).strip();
            }
        }
        return "";
    }

    private static String body(String text) {
        if (text == null || !text.startsWith("---")) {
            return text == null ? "" : text;
        }
        String[] parts = text.split("\\R---\\R", 2);
        return parts.length == 2 ? parts[1] : text;
    }

    private static String summarize(String text) {
        String compact = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (compact.isBlank()) {
            return "无正文摘要";
        }
        return compact.length() <= 80 ? compact : compact.substring(0, 79) + "…";
    }

    private static String inferTypeFromFilename(String filename) {
        for (NoteType type : NoteType.values()) {
            if (filename.startsWith(type.wire() + "_")) {
                return type.wire();
            }
        }
        return NoteType.REFERENCE_MATERIAL.wire();
    }

    private static String safeSlug(String value) {
        return (value == null ? "" : value.toLowerCase())
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private static String nonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String now() {
        return OffsetDateTime.now().toString();
    }

    private static String trimBytes(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String value = new String(Character.toChars(cp));
            int len = value.getBytes(StandardCharsets.UTF_8).length;
            if (used + len > maxBytes) {
                break;
            }
            out.append(value);
            used += len;
            i += Character.charCount(cp);
        }
        return out.toString().stripTrailing();
    }
}
