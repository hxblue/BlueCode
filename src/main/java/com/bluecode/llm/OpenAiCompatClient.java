package com.bluecode.llm;

import com.bluecode.config.ProviderConfig;

public class OpenAiCompatClient extends OpenAiClient {
    public OpenAiCompatClient(ProviderConfig config) {
        super(config, true);
    }

    public OpenAiCompatClient(ProviderConfig config, String ignoredSystemPrompt) {
        this(config);
    }
}
