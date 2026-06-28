package com.bluecode.compact;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public final class Recovery {
    public static final String BOUNDARY_NOTICE = """
            需要文件原文、错误原文或用户原话时,请使用文件读取工具重新读取对应路径或询问用户。
            不要依据摘要内容猜测完整文件、完整错误输出或未展示的用户原话。
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Recovery() {
    }

    public static String buildRecoveryAttachment(List<FileReadRecord> snapshot, List<Map<String, Object>> toolDefs) {
        StringBuilder builder = new StringBuilder();
        builder.append("## 最近读过的文件\n");
        List<FileReadRecord> files = snapshot == null ? List.of() : snapshot;
        if (files.isEmpty()) {
            builder.append("(无)\n");
        } else {
            files.stream()
                    .limit(CompactConstants.RECOVERY_FILE_LIMIT)
                    .forEach(record -> builder.append(renderFileBlock(record)).append('\n'));
        }
        builder.append("\n## 当前可用工具\n").append(renderToolsBlock(toolDefs));
        builder.append("\n## 边界提示\n").append(BOUNDARY_NOTICE.strip());
        return builder.toString();
    }

    static String renderFileBlock(FileReadRecord record) {
        int charLimit = (int) (CompactConstants.RECOVERY_TOKENS_PER_FILE
                * CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
        String content = record.content() == null ? "" : record.content();
        boolean truncated = content.length() > charLimit;
        String fragment = truncated ? content.substring(0, charLimit) : content;
        StringBuilder builder = new StringBuilder();
        builder.append("### ").append(record.path()).append('\n');
        builder.append("[read at] ").append(record.timestamp()).append('\n');
        builder.append(fragment);
        if (truncated) {
            builder.append('\n').append("(content truncated)");
        }
        builder.append('\n');
        return builder.toString();
    }

    static String renderToolsBlock(List<Map<String, Object>> defs) {
        StringBuilder builder = new StringBuilder();
        for (Map<String, Object> def : defs == null ? List.<Map<String, Object>>of() : defs) {
            builder.append("- ")
                    .append(def.getOrDefault("name", ""))
                    .append(": ")
                    .append(def.getOrDefault("description", ""))
                    .append('\n');
            Object schema = def.containsKey("input_schema") ? def.get("input_schema") : def.get("parameters");
            try {
                builder.append("  schema: ").append(MAPPER.writeValueAsString(schema)).append('\n');
            } catch (Exception e) {
                builder.append("  schema: ").append(String.valueOf(schema)).append('\n');
            }
        }
        if (builder.isEmpty()) {
            builder.append("(无)\n");
        }
        return builder.toString();
    }

    public record FileReadRecord(String path, String content, Instant timestamp) {
        public FileReadRecord {
            path = path == null ? "" : path;
            content = content == null ? "" : content;
            timestamp = timestamp == null ? Instant.EPOCH : timestamp;
        }
    }

    public static final class RecoveryState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, FileReadRecord> files = new LinkedHashMap<>();

        public void recordFile(String path, String content) {
            if (path == null || path.isBlank()) {
                return;
            }
            String normalized = Path.of(path).toAbsolutePath().normalize().toString();
            lock.lock();
            try {
                files.put(normalized, new FileReadRecord(normalized, content, Instant.now()));
            } finally {
                lock.unlock();
            }
        }

        public List<FileReadRecord> snapshot() {
            lock.lock();
            try {
                List<FileReadRecord> copy = new ArrayList<>(files.values());
                copy.sort(Comparator.comparing(FileReadRecord::timestamp).reversed());
                return List.copyOf(copy);
            } finally {
                lock.unlock();
            }
        }

        public void reset() {
            lock.lock();
            try {
                files.clear();
            } finally {
                lock.unlock();
            }
        }
    }
}
