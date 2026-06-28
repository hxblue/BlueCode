package com.bluecode.tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class BashTool implements Tool {
    private final Duration timeout;

    public BashTool() {
        this(Registry.DEFAULT_TIMEOUT);
    }

    BashTool(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public String name() {
        return "Bash";
    }

    @Override
    public String description() {
        return "在当前工作目录执行 shell 命令，返回 stdout、stderr 和 exit_code。读文件、找文件、搜内容请优先用 ReadFile/Glob/Grep，不要用 Bash 拼凑。";
    }

    @Override
    public Map<String, Object> schema() {
        return ToolSchemas.object(ToolSchemas.properties(
                "command", "要执行的 shell 命令。", "echo hello"
        ), "command");
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(ToolContext ctx, Map<String, Object> args) {
        try {
            String command = ToolArgs.requiredString(args, "command");
            ProcessBuilder builder = commandProcess(command);
            builder.directory(ctx.resolvePath("").toFile());
            Process process = builder.start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outReader = Thread.startVirtualThread(() -> read(process.getInputStream(), stdout));
            Thread errReader = Thread.startVirtualThread(() -> read(process.getErrorStream(), stderr));

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                outReader.join(1000);
                errReader.join(1000);
                return Result.error("命令超时(超过 " + timeout.toSeconds() + " 秒):\n" + command);
            }

            outReader.join(1000);
            errReader.join(1000);
            int exitCode = process.exitValue();
            String output = """
                exit_code: %d
                stdout:
                %s
                stderr:
                %s
                """.formatted(exitCode, stdout.toString(StandardCharsets.UTF_8), stderr.toString(StandardCharsets.UTF_8)).stripTrailing();
            output = Truncate.byChars(output, 30000);
            return exitCode == 0 ? Result.ok(output) : Result.error(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error("命令执行被中断");
        } catch (IOException e) {
            return Result.error("命令执行失败: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    private ProcessBuilder commandProcess(String command) {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        return windows ? new ProcessBuilder("cmd", "/C", command) : new ProcessBuilder("sh", "-c", command);
    }

    private void read(java.io.InputStream input, ByteArrayOutputStream output) {
        try (input; output) {
            input.transferTo(output);
        } catch (IOException ignored) {
            // 进程被强制结束或管道关闭时，主结果会体现为超时或执行失败。
        }
    }
}
