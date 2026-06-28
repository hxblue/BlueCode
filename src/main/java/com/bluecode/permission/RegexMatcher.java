package com.bluecode.permission;

import java.util.regex.Pattern;

public record RegexMatcher(Pattern compiled, String source) implements Matcher {
    public RegexMatcher {
        if (compiled == null) {
            throw new IllegalArgumentException("compiled 不能为空");
        }
        source = source == null ? "" : source;
    }

    @Override
    public boolean match(String text) {
        return compiled.matcher(text == null ? "" : text).find();
    }

    @Override
    public String describe() {
        return "~" + source;
    }
}
