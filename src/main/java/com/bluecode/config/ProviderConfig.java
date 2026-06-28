package com.bluecode.config;

public class ProviderConfig {
    private String name;
    private String protocol;
    private String baseUrl;
    private String apiKey;
    private String model;
    private boolean thinking;
    private int contextWindow;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public boolean isThinking() {
        return thinking;
    }

    public void setThinking(boolean thinking) {
        this.thinking = thinking;
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    public int effectiveContextWindow() {
        if (contextWindow > 0) {
            return contextWindow;
        }
        return switch (protocol == null ? "" : protocol) {
            case "openai" -> ProtocolDefaults.DEFAULT_OPENAI_CONTEXT_WINDOW;
            case "anthropic" -> ProtocolDefaults.DEFAULT_ANTHROPIC_CONTEXT_WINDOW;
            default -> ProtocolDefaults.DEFAULT_ANTHROPIC_CONTEXT_WINDOW;
        };
    }
}
