package com.bluecode.tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

public final class GrepTool implements Tool {
    private static final int MAX_LINE_LENGTH = 1024 * 1024;

    @Override
    public String name() {
        return "Grep";
    }

    @Override
    public String description() {
        return "使用 Java 正则在文件内容中搜索，返回 file:line:content 命中位置。";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchemas.object(ToolSchemas.properties(
                "pattern", "Java Pattern 正则表达式。", "class\\s+BlueCode",
                "path", "可选搜索根目录，默认当前工作目录。", ".",
                "glob", "可选文件名过滤 glob，例如 **/*.java。", "**/*.java"
        ), "pattern");
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            Pattern pattern = Pattern.compile(ToolArgs.requiredString(args, "pattern"));
            Path root = ctx.resolvePath(ToolArgs.optionalString(args, "path", "."));
            String glob = ToolArgs.optionalString(args, "glob", "");
            if (!Files.exists(root)) {
                return Result.error("路径不存在: " + root);
            }
            List<String> matches = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    if (!glob.isBlank() && !GlobMatcher.matches(glob, relative)) {
                        continue;
                    }
                    searchFile(root, file, pattern, matches);
                    if (matches.size() > 100) {
                        break;
                    }
                }
            }
            if (matches.isEmpty()) {
                return Result.ok("无命中");
            }
            String content = String.join("\n", matches.subList(0, Math.min(100, matches.size())));
            if (matches.size() > 100) {
                content += "\n[truncated]";
            }
            return Result.ok(content);
        } catch (PatternSyntaxException e) {
            return Result.error("正则表达式无效: " + e.getMessage());
        } catch (IOException e) {
            return Result.error("搜索失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    private void searchFile(Path root, Path file, Pattern pattern, List<String> matches) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.length() > MAX_LINE_LENGTH) {
                    continue;
                }
                if (pattern.matcher(line).find()) {
                    String relative = root.relativize(file).toString().replace('\\', '/');
                    matches.add(relative + ":" + lineNumber + ":" + line);
                    if (matches.size() > 100) {
                        return;
                    }
                }
            }
        } catch (IOException ignored) {
            // 单个文件不可读时跳过，整体搜索继续。
        }
    }
}
