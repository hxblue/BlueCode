package com.bluecode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class ReadFileTool implements Tool {
    @Override
    public String name() {
        return "ReadFile";
    }

    @Override
    public String description() {
        return "读取指定文本文件，返回带行号的内容。";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchemas.object(ToolSchemas.properties(
                "path", "要读取的文件路径，可以是相对当前工作目录的路径。", "src/main/java/com/bluecode/bluecode.java"
        ), "path");
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            Path path = ctx.resolvePath(ToolArgs.requiredString(args, "path"));
            if (!Files.exists(path)) {
                return Result.error("文件不存在: " + path);
            }
            if (Files.isDirectory(path)) {
                return Result.error("目标是目录，不是文件: " + path);
            }
            String content = Files.readString(path);
            return Result.ok(Truncate.byLinesAndBytes(numberLines(content), 2000, 256 * 1024));
        } catch (IOException e) {
            return Result.error("读取文件失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    private String numberLines(String content) {
        StringBuilder result = new StringBuilder();
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                result.append('\n');
            }
            result.append(String.format("%6d\t%s", i + 1, lines[i]));
        }
        return result.toString();
    }
}
