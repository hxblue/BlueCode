package com.bluecode.permission;

public record GlobMatcher(String pattern, boolean command) implements Matcher {
    public GlobMatcher {
        pattern = pattern == null ? "" : pattern;
    }

    @Override
    public boolean match(String text) {
        return Rule.matchPattern(pattern, text == null ? "" : text, !command);
    }

    @Override
    public String describe() {
        return pattern;
    }
}
