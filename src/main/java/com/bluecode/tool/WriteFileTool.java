package com.bluecode.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class WriteFileTool implements Tool {
    @Override
    public String name() {
        return "WriteFile";
    }

    @Override
    public String description() {
        return "创建或覆盖文本文件，父目录不存在时会自动创建。";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchemas.object(ToolSchemas.properties(
                "path", "要写入的文件路径。", "notes/output.txt",
                "content", "要写入文件的完整文本内容。", "hello"
        ), "path", "content");
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            Path path = ctx.resolvePath(ToolArgs.requiredString(args, "path"));
            Object contentValue = args.get("content");
            if (!(contentValue instanceof String content)) {
                return Result.error("缺少必填参数: content");
            }
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, content);
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            return Result.ok("已写入 " + path + ": " + bytes + " 字节");
        } catch (IOException e) {
            return Result.error("写入文件失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
