package com.bluecode.llm;

import com.bluecode.config.ProviderConfig;

import java.util.concurrent.BlockingQueue;

public interface LlmClient {
    BlockingQueue<StreamEvent> stream(Request request);

    String model();

    static LlmClient create(ProviderConfig config) {
        return switch (config.getProtocol()) {
            case "anthropic" -> new AnthropicClient(config);
            case "openai" -> new OpenAiClient(config, false);
            case "openai-compat" -> new OpenAiCompatClient(config);
            default -> throw new IllegalArgumentException("不支持的 provider 协议: " + config.getProtocol());
        };
    }

    static LlmClient create(ProviderConfig config, String ignoredSystemPrompt) {
        return create(config);
    }
}
