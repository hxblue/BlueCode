package com.bluecode.prompt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

public record Environment(String workingDir, String platform, String date, String gitStatus, String version,
                          String model) {
    public Environment {
        workingDir = clean(workingDir);
        platform = clean(platform);
        date = clean(date);
        gitStatus = clean(gitStatus);
        version = clean(version);
        model = clean(model);
    }

    public static Environment gather(String version, String model) {
        String workingDir = property("user.dir");
        return new Environment(
                workingDir,
                property("os.name"),
                LocalDate.now().toString(),
                gatherGitStatus(workingDir),
                version,
                model);
    }

    public String render() {
        StringBuilder builder = new StringBuilder("环境信息:");
        append(builder, "工作目录", workingDir);
        append(builder, "平台", platform);
        append(builder, "当前日期", date);
        append(builder, "Git 状态", gitStatus);
        append(builder, "应用版本", version);
        append(builder, "当前模型", model);
        return builder.toString();
    }

    private static void append(StringBuilder builder, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append('\n').append(key).append(": ").append(value);
    }

    private static String property(String key) {
        return clean(java.lang.System.getProperty(key));
    }

    private static String gatherGitStatus(String workingDir) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("git", "status", "--porcelain")
                    .redirectErrorStream(true);
            if (workingDir != null && !workingDir.isBlank()) {
                builder.directory(Path.of(workingDir).toFile());
            }
            process = builder.start();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Process running = process;
            Thread reader = Thread.startVirtualThread(() -> readAll(running, output));
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                reader.join(1000);
                return "";
            }
            reader.join(1000);
            if (process.exitValue() != 0) {
                return "";
            }
            String raw = output.toString(StandardCharsets.UTF_8).strip();
            if (raw.isBlank()) {
                return "clean";
            }
            long changed = raw.lines().count();
            return changed + " 个文件有变更";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (IOException ignored) {
            return "";
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void readAll(Process process, ByteArrayOutputStream output) {
        try (var input = process.getInputStream()) {
            input.transferTo(output);
        } catch (IOException ignored) {
            // git 不可用、进程被结束或管道关闭时，环境信息降级为空。
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
