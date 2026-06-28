package com.bluecode.permission;

public record ExactMatcher(String value) implements Matcher {
    public ExactMatcher {
        value = value == null ? "" : value;
    }

    @Override
    public boolean match(String text) {
        return value.equals(text == null ? "" : text);
    }

    @Override
    public String describe() {
        return "=" + value;
    }
}
