package com.bluecode.memory;

import com.bluecode.conversation.Message;
import com.bluecode.llm.LlmClient;
import com.bluecode.llm.Request;
import com.bluecode.llm.StreamEvent;
import com.bluecode.llm.SystemPrompt;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public final class Manager {
    private static final Logger LOGGER = Logger.getLogger(Manager.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_INDEX_BYTES = 25 * 1024;

    private final Store projectStore;
    private final Store userStore;
    private final ReentrantLock updateLock = new ReentrantLock();

    private volatile LlmClient client;
    private volatile String model;

    public Manager(Path projectDir, Path userDir, LlmClient client, String model) {
        this.projectStore = new Store(projectDir);
        this.userStore = new Store(userDir);
        this.client = client;
        this.model = model == null ? "" : model;
    }

    public Store projectStore() {
        return projectStore;
    }

    public Store userStore() {
        return userStore;
    }

    public void setProvider(LlmClient client, String model) {
        this.client = client;
        this.model = model == null ? "" : model;
    }

    public String loadIndex() {
        try {
            String project = projectStore.loadIndex().strip();
            String user = userStore.loadIndex().strip();
            String merged = String.join("\n\n", List.of(project, user).stream().filter(s -> !s.isBlank()).toList());
            return truncateIndex(merged);
        } catch (Exception e) {
            LOGGER.warning("读取长期记忆索引失败: " + e.getMessage());
            return "";
        }
    }

    public void updateAsync(List<Message> recentMessages) {
        LlmClient active = client;
        if (active == null || recentMessages == null || recentMessages.isEmpty()) {
            return;
        }
        Thread.startVirtualThread(() -> update(active, recentMessages));
    }

    private void update(LlmClient active, List<Message> recentMessages) {
        updateLock.lock();
        try {
            String requestText = buildUpdateRequest(recentMessages);
            Request request = new Request(
                    List.of(new Message(Message.Role.USER, requestText)),
                    List.of(),
                    new SystemPrompt(PromptTemplates.UPDATE_SYSTEM, ""),
                    "");
            String reply = collect(active.stream(request));
            List<UpdateAction> actions = parseActions(reply);
            if (actions.isEmpty()) {
                return;
            }
            projectStore.apply(actions.stream().filter(action -> "project".equals(action.level())).toList());
            userStore.apply(actions.stream().filter(action -> "user".equals(action.level())).toList());
        } catch (Exception e) {
            LOGGER.warning("长期记忆更新失败: " + e.getMessage());
        } finally {
            updateLock.unlock();
        }
    }

    private String buildUpdateRequest(List<Message> recentMessages) {
        StringBuilder builder = new StringBuilder();
        builder.append("当前模型: ").append(model == null ? "" : model).append("\n\n");
        builder.append("## 已有项目级索引\n").append(safeLoad(projectStore)).append("\n\n");
        builder.append("## 已有用户级索引\n").append(safeLoad(userStore)).append("\n\n");
        builder.append("## 最近一轮对话\n");
        for (Message message : recentMessages) {
            builder.append(message.role()).append(": ").append(message.content()).append('\n');
            if (!message.toolCalls().isEmpty()) {
                builder.append("tool_calls: ").append(message.toolCalls()).append('\n');
            }
            if (!message.toolResults().isEmpty()) {
                builder.append("tool_results: ").append(message.toolResults()).append('\n');
            }
        }
        return builder.toString();
    }

    private static String safeLoad(Store store) {
        try {
            return store.loadIndex();
        } catch (Exception e) {
            return "";
        }
    }

    private static String collect(BlockingQueue<StreamEvent> stream) throws InterruptedException {
        StringBuilder text = new StringBuilder();
        while (true) {
            StreamEvent event = stream.poll(100, TimeUnit.MILLISECONDS);
            if (event == null) {
                continue;
            }
            switch (event) {
                case StreamEvent.TextDelta delta -> text.append(delta.text());
                case StreamEvent.StreamEnd ignored -> {
                    return text.toString();
                }
                case StreamEvent.Error error -> throw new IllegalStateException(error.message(), error.cause());
                default -> {
                }
            }
        }
    }

    private static List<UpdateAction> parseActions(String reply) throws Exception {
        String json = extractJsonArray(reply);
        if (json.isBlank()) {
            return List.of();
        }
        List<UpdateAction> actions = MAPPER.readValue(json, new TypeReference<>() {
        });
        return actions == null ? List.of() : List.copyOf(actions);
    }

    private static String extractJsonArray(String text) {
        if (text == null) {
            return "";
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end < start) {
            return "";
        }
        return text.substring(start, end + 1);
    }

    private static String truncateIndex(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_INDEX_BYTES) {
            return text;
        }
        String suffix = "\n(index truncated)";
        int limit = MAX_INDEX_BYTES - suffix.getBytes(StandardCharsets.UTF_8).length;
        List<Integer> cps = new ArrayList<>();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            cps.add(cp);
            i += Character.charCount(cp);
        }
        StringBuilder out = new StringBuilder();
        int used = 0;
        for (int cp : cps) {
            String value = new String(Character.toChars(cp));
            int len = value.getBytes(StandardCharsets.UTF_8).length;
            if (used + len > limit) {
                break;
            }
            out.append(value);
            used += len;
        }
        return out.toString().stripTrailing() + suffix;
    }
}
