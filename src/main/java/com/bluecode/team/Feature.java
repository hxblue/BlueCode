package com.bluecode.team;

import com.bluecode.config.AppConfig;

import java.util.Locale;

public final class Feature {
    private Feature() {
    }

    public static boolean forkTeammateEnabled(AppConfig config) {
        return (config != null && config.getFeatures().forkTeammate()) || envTruthy(System.getenv("MEWCODE_FORK_TEAMMATE"));
    }

    public static boolean envTruthy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("1") || normalized.equals("true") || normalized.equals("yes");
    }
}
