package com.bluecode.coordinator;

import com.bluecode.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatorTest {
    @Test
    void featureFlagMustBeEnabledInConfigToo() {
        AppConfig config = new AppConfig();
        config.setFeatures(new AppConfig.FeaturesConfig(false, false));

        assertFalse(Coordinator.isEnabled(config));
    }

    @Test
    void allowedToolsDoNotIncludeWriteOrEdit() {
        assertTrue(Coordinator.allowedTools().contains("Bash"));
        assertFalse(Coordinator.allowedTools().contains("WriteFile"));
        assertFalse(Coordinator.allowedTools().contains("EditFile"));
    }
}
