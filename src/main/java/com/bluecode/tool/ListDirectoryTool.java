package com.bluecode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public final class ListDirectoryTool implements Tool {
    private static final int MAX_ENTRIES = 200;

    @Override
    public String name() {
        return "ListDirectory";
    }

    @Override
    public String description() {
        return "列出指定目录下的子目录和文件。非递归模式只列出一层，递归模式列出所有层级。"
                + "用于快速了解项目结构，比 Glob 更适合浏览目录树。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> recursiveSchema = new LinkedHashMap<>();
        recursiveSchema.put("type", "boolean");
        recursiveSchema.put("description", "是否递归列出所有子目录，默认为 false。");

        Map<String, Object> pathSchema = new LinkedHashMap<>();
        pathSchema.put("type", "string");
        pathSchema.put("description", "要列出的目录路径，可以是相对路径。");
        pathSchema.put("example", "src/main/java");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", pathSchema);
        properties.put("recursive", recursiveSchema);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of("path"));
        return schema;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            String path = ToolArgs.requiredString(args, "path");
            boolean recursive = parseRecursive(args.get("recursive"));

            Path dir = ctx.resolvePath(path);
            if (!Files.exists(dir)) {
                return Result.error("目录不存在: " + dir);
            }
            if (!Files.isDirectory(dir)) {
                return Result.error("目标不是目录: " + dir);
            }

            String output = recursive ? listRecursive(dir) : listSingle(dir);
            return Result.ok(output);
        } catch (IOException e) {
            return Result.error("列出目录失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    private boolean parseRecursive(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return false;
    }

    private String listSingle(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            StringBuilder builder = new StringBuilder();
            builder.append(dir.toString().replace('\\', '/')).append('\n');

            Path[] children = stream.sorted(Comparator
                    .comparing((Path p) -> Files.isDirectory(p) ? 0 : 1)
                    .thenComparing(p -> p.getFileName().toString()))
                    .toArray(Path[]::new);

            for (Path child : children) {
                String marker = Files.isDirectory(child) ? "[dir] " : "[file] ";
                builder.append("  ").append(marker).append(child.getFileName().toString()).append('\n');
            }
            return builder.toString().stripTrailing();
        }
    }

    private String listRecursive(Path dir) throws IOException {
        try (Stream<Path> stream = Files.walk(dir)) {
            StringBuilder builder = new StringBuilder();
            builder.append(dir.toString().replace('\\', '/')).append('\n');

            Path[] entries = stream
                    .filter(p -> !p.equals(dir))
                    .sorted(Comparator
                            .comparing((Path p) -> dir.relativize(p).toString().replace('\\', '/'))
                            .thenComparing(p -> Files.isDirectory(p) ? 0 : 1))
                    .toArray(Path[]::new);

            int limit = Math.min(entries.length, MAX_ENTRIES);
            for (int i = 0; i < limit; i++) {
                Path entry = entries[i];
                String relative = dir.relativize(entry).toString().replace('\\', '/');
                int depth = dir.relativize(entry).getNameCount();
                String indent = "  ".repeat(depth);
                String marker = Files.isDirectory(entry) ? "[dir] " : "[file] ";
                builder.append(indent).append(marker).append(relative).append('\n');
            }

            if (entries.length > MAX_ENTRIES) {
                builder.append("  [truncated, total: ").append(entries.length).append(" entries]\n");
            }
            return builder.toString().stripTrailing();
        }
    }
}
