package com.bluecode.permission;

public record NotMatcher(Matcher inner) implements Matcher {
    public NotMatcher {
        if (inner == null) {
            throw new IllegalArgumentException("inner 不能为空");
        }
    }

    @Override
    public boolean match(String text) {
        return !inner.match(text);
    }

    @Override
    public String describe() {
        return "!" + inner.describe();
    }
}
