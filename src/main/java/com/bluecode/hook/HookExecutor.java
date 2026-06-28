package com.bluecode.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HookExecutor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TEMPLATE_FIELD = Pattern.compile("\\$\\{([^}]+)}");

    private final HttpClient httpClient;

    public HookExecutor() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    HookExecutor(HttpClient httpClient) {
        this.httpClient = httpClient == null ? HttpClient.newHttpClient() : httpClient;
    }

    public ExecutionResult run(HookRule rule, Payload payload, boolean blocking) {
        Payload effectivePayload = payload == null ? new Payload(Map.of()) : payload;
        return switch (rule.action()) {
            case Action.Shell shell -> runShell(shell, effectivePayload, blocking, rule.timeout());
            case Action.Prompt prompt -> new ExecutionResult(false, "", prompt.text(), null);
            case Action.Http http -> runHttp(http, effectivePayload, blocking, rule.timeout());
            case Action.Subagent ignored -> runSubagent(rule);
        };
    }

    private ExecutionResult runShell(Action.Shell action, Payload payload, boolean blocking, Duration timeout) {
        Process process = null;
        try {
            process = commandProcess(action.command()).start();
            Process running = process;
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Thread outReader = Thread.startVirtualThread(() -> read(running.getInputStream(), stdout));
            Thread errReader = Thread.startVirtualThread(() -> read(running.getErrorStream(), stderr));
            try {
                running.getOutputStream().write((payload.toSortedJson() + "\n").getBytes(StandardCharsets.UTF_8));
                running.getOutputStream().flush();
            } catch (IOException ignored) {
                // 脚本不读 stdin 或提前退出时忽略，退出码会表达真实结果。
            } finally {
                try {
                    running.getOutputStream().close();
                } catch (IOException ignored) {
                }
            }

            boolean finished = running.waitFor(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            if (!finished) {
                running.destroyForcibly();
                joinReader(outReader);
                joinReader(errReader);
                return new ExecutionResult(false, "", "", new TimeoutException("timeout after " + timeout));
            }
            joinReader(outReader);
            joinReader(errReader);
            int code = running.exitValue();
            String out = stdout.toString(StandardCharsets.UTF_8).stripTrailing();
            String err = stderr.toString(StandardCharsets.UTF_8).stripTrailing();
            if (blocking && code == 2) {
                String reason = err.isBlank() ? out : err;
                return new ExecutionResult(true, reason, "", null);
            }
            if (code == 0) {
                return ExecutionResult.empty();
            }
            String detail = err.isBlank() ? out : err;
            return new ExecutionResult(false, "", "", new RuntimeException("exit " + code + (detail.isBlank() ? "" : ": " + detail)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new ExecutionResult(false, "", "", e);
        } catch (IOException e) {
            return new ExecutionResult(false, "", "", e);
        }
    }

    private ExecutionResult runHttp(Action.Http action, Payload payload, boolean blocking, Duration timeout) {
        try {
            String body = action.bodyTemplate() == null ? payload.toSortedJson() : renderTemplate(action.bodyTemplate(), payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(action.url()))
                    .timeout(timeout);
            boolean hasContentType = action.headers().keySet().stream().anyMatch(key -> key.equalsIgnoreCase("content-type"));
            for (Map.Entry<String, String> header : action.headers().entrySet()) {
                builder.header(header.getKey(), header.getValue());
            }
            if (!hasContentType && body != null && !body.isBlank()) {
                builder.header("Content-Type", "application/json; charset=utf-8");
            }
            HttpRequest.BodyPublisher publisher = body == null || body.isBlank()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
            HttpRequest request = builder.method(action.method(), publisher).build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new ExecutionResult(false, "", "", new RuntimeException("http status " + response.statusCode()));
            }
            String responseBody = response.body() == null ? "" : response.body().strip();
            if (responseBody.isBlank()) {
                return ExecutionResult.empty();
            }
            JsonNode root = MAPPER.readTree(responseBody);
            if (blocking && "block".equalsIgnoreCase(root.path("decision").asText(""))) {
                return new ExecutionResult(true, root.path("reason").asText("blocked by hook"), "", null);
            }
            return ExecutionResult.empty();
        } catch (Exception e) {
            return new ExecutionResult(false, "", "", e);
        }
    }

    private ExecutionResult runSubagent(HookRule rule) {
        System.err.printf("[hook subagent] not yet implemented, skipped: %s%n", rule.name());
        return ExecutionResult.empty();
    }

    private String renderTemplate(String template, Payload payload) {
        Matcher matcher = TEMPLATE_FIELD.matcher(template == null ? "" : template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(payload.getByPath(matcher.group(1).strip())));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private ProcessBuilder commandProcess(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            return new ProcessBuilder("cmd", "/C", windowsCommand(command));
        }
        return new ProcessBuilder("sh", "-c", command);
    }

    private String windowsCommand(String command) {
        String normalized = command == null ? "" : command;
        normalized = normalized.replace(">&2", "1>&2");
        normalized = normalized.replace(";", "&");
        normalized = normalized.replaceAll("(?i)\\bexit\\s+(\\d+)\\b", "exit /b $1");
        return normalized;
    }

    private void read(java.io.InputStream input, ByteArrayOutputStream output) {
        try (input; output) {
            input.transferTo(output);
        } catch (IOException ignored) {
            // 进程结束或被超时杀掉时，主流程会返回对应结果。
        }
    }

    private void joinReader(Thread reader) throws InterruptedException {
        if (reader != null) {
            reader.join(1000);
        }
    }
}
