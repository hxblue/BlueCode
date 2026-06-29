package com.bluecode.teams;

import java.util.List;

public final class TeammateToolFilter {
    private TeammateToolFilter() {
    }

    public static List<String> allowedTools() {
        return com.bluecode.tool.Filter.TEAMMATE_ALLOWED_TOOLS;
    }
}
