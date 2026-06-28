package com.bluecode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class GlobTool implements Tool {
    @Override
    public String name() {
        return "Glob";
    }

    @Override
    public String description() {
        return "按 glob 模式查找文件路径，支持 ** 匹配任意层级目录。";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchemas.object(ToolSchemas.properties(
                "pattern", "glob 模式，例如 **/*.java。", "**/*.java",
                "path", "可选搜索根目录，默认当前工作目录。", "."
        ), "pattern");
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            String pattern = ToolArgs.requiredString(args, "pattern");
            Path root = ctx.resolvePath(ToolArgs.optionalString(args, "path", "."));
            if (!Files.exists(root)) {
                return Result.error("路径不存在: " + root);
            }
            try (Stream<Path> walk = Files.walk(root)) {
                List<String> matches = walk
                        .filter(Files::isRegularFile)
                        .map(path -> root.relativize(path).toString().replace('\\', '/'))
                        .filter(path -> GlobMatcher.matches(pattern, path))
                        .sorted(Comparator.naturalOrder())
                        .limit(101)
                        .toList();
                if (matches.isEmpty()) {
                    return Result.ok("无匹配");
                }
                String content = String.join("\n", matches.subList(0, Math.min(100, matches.size())));
                if (matches.size() > 100) {
                    content += "\n[truncated]";
                }
                return Result.ok(content);
            }
        } catch (IOException e) {
            return Result.error("查找文件失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
