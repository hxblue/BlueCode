package com.bluecode.smoke;

import com.bluecode.agent.Agent;
import com.bluecode.agent.CancelToken;
import com.bluecode.agent.CompactEvent;
import com.bluecode.agent.Event;
import com.bluecode.agent.Phase;
import com.bluecode.agent.SessionRuntime;
import com.bluecode.config.AppConfig;
import com.bluecode.config.ConfigLoader;
import com.bluecode.config.ProviderConfig;
import com.bluecode.conversation.ConversationManager;
import com.bluecode.llm.LlmClient;
import com.bluecode.permission.Mode;
import com.bluecode.permission.Outcome;
import com.bluecode.permission.PermissionEngine;
import com.bluecode.tool.Registry;

import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;

public final class SmokeBlueCode {
    private static final String CONFIG_PATH = ".bluecode/config.yaml";

    private SmokeBlueCode() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = ConfigLoader.load(CONFIG_PATH);
        ProviderConfig provider = config.getProviders().getFirst();
        PermissionEngine engine = PermissionEngine.create(Path.of("").toAbsolutePath());
        SessionRuntime runtime = SessionRuntime.create(Path.of("").toAbsolutePath(), provider.effectiveContextWindow());
        Agent agent = new Agent(LlmClient.create(provider), Registry.createDefault(), "smoke", engine, runtime);
        ConversationManager conversation = new ConversationManager();

        String first = args.length > 0 ? args[0] : "请用一句话回答: BlueCode smoke 第一轮。";
        String second = args.length > 1 ? args[1] : "继续用一句话回答，并保持简短。";
        runTurn(agent, conversation, first);
        runTurn(agent, conversation, second);
    }

    private static void runTurn(Agent agent, ConversationManager conversation, String prompt) throws InterruptedException {
        System.out.println("\nUSER> " + prompt);
        conversation.addUserMessage(prompt);
        BlockingQueue<Event> queue = agent.run(conversation, Mode.BYPASS, new CancelToken());
        boolean done = false;
        while (!done) {
            Event event = queue.take();
            switch (event) {
                case Event.Text text -> {
                    System.out.print(text.delta());
                    System.out.flush();
                }
                case Event.Tool tool -> printToolEvent(tool.event());
                case Event.UsageReport usage -> System.err.printf(
                        "%n[usage] input=%d output=%d cache_write=%d cache_read=%d%n",
                        usage.usage().input(),
                        usage.usage().output(),
                        usage.usage().cacheWrite(),
                        usage.usage().cacheRead());
                case Event.Iter iter -> System.err.printf("%n[iter] %d%n", iter.value());
                case Event.Notice notice -> System.err.println(notice.message());
                case Event.Approval approval -> approval.request().respond().offer(Outcome.DENY_ONCE);
                case CompactEvent compact -> System.err.println("[compact] " + compact.phase());
                case Event.Done ignored -> {
                    System.out.println();
                    done = true;
                }
                case Event.Failed failed -> {
                    System.err.println("错误: " + failed.message());
                    done = true;
                }
            }
        }
    }

    private static void printToolEvent(com.bluecode.agent.ToolEvent event) {
        if (event.phase() == Phase.START) {
            System.err.println("\n* " + event.name() + "(" + event.args() + ")");
            return;
        }
        String[] lines = event.result() == null ? new String[0] : event.result().strip().split("\\R", -1);
        int limit = Math.min(4, lines.length);
        for (int i = 0; i < limit; i++) {
            System.err.println("  - " + lines[i]);
        }
        if (lines.length > limit) {
            System.err.println("  - [truncated]");
        }
    }
}
