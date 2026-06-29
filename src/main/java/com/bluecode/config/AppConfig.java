package com.bluecode.config;

import java.util.ArrayList;
import java.util.List;

public class AppConfig {
    private List<ProviderConfig> providers = new ArrayList<>();
    private Boolean enableSubAgentBackground;
    private FeaturesConfig features = new FeaturesConfig(false, false);

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

    public FeaturesConfig getFeatures() {
        return features == null ? new FeaturesConfig(false, false) : features;
    }

    public void setFeatures(FeaturesConfig features) {
        this.features = features == null ? new FeaturesConfig(false, false) : features;
    }

    public record FeaturesConfig(boolean coordinatorMode, boolean forkTeammate) {
    }
}
