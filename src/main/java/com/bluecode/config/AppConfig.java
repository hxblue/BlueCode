package com.bluecode.config;

import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    private List<ProviderConfig> providers = new ArrayList<>();
    private Boolean enableSubAgentBackground;

    public List<ProviderConfig> getProviders() {
        return providers;
    }

    public void setProviders(List<ProviderConfig> providers) {
        this.providers = providers == null ? new ArrayList<>() : providers;
    }

    public Boolean getEnableSubAgentBackground() {
        return enableSubAgentBackground;
    }

    public void setEnableSubAgentBackground(Boolean enableSubAgentBackground) {
        this.enableSubAgentBackground = enableSubAgentBackground;
    }

    public boolean effectiveEnableSubAgentBackground() {
        return enableSubAgentBackground == null || enableSubAgentBackground;
    }
}
