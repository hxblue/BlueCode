package com.bluecode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class EditFileTool implements Tool {
    @Override
    public String name() {
        return "EditFile";
    }

    @Override
    public String description() {
        return "在文件中把唯一匹配的 old_string 替换为 new_string；匹配 0 次或多次都会返回错误。编辑前请先用 ReadFile 读取目标文件，确认 old_string 唯一。";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchemas.object(ToolSchemas.properties(
                "path", "要修改的文件路径。", "src/main/java/App.java",
                "old_string", "待替换的原文片段，必须在文件中唯一出现。", "old text",
                "new_string", "替换后的新文本。", "new text"
        ), "path", "old_string", "new_string");
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            Path path = ctx.resolvePath(ToolArgs.requiredString(args, "path"));
            String oldString = ToolArgs.requiredString(args, "old_string");
            Object newValue = args.get("new_string");
            if (!(newValue instanceof String newString)) {
                return Result.error("缺少必填参数: new_string");
            }
            if (!Files.exists(path)) {
                return Result.error("文件不存在: " + path);
            }
            if (Files.isDirectory(path)) {
                return Result.error("目标是目录，不是文件: " + path);
            }
            String content = Files.readString(path);
            int count = countOccurrences(content, oldString);
            if (count == 0) {
                return Result.error("未找到匹配的内容");
            }
            if (count > 1) {
                return Result.error("匹配到 " + count + " 处，old_string 不唯一，请提供更长上下文");
            }
            Files.writeString(path, content.replace(oldString, newString));
            return Result.ok("已修改 " + path);
        } catch (IOException e) {
            return Result.error("修改文件失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    private int countOccurrences(String text, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
