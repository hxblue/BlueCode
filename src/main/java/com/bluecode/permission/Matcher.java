package com.bluecode.permission;

public sealed interface Matcher permits ExactMatcher, GlobMatcher, RegexMatcher, NotMatcher {
    boolean match(String text);

    String describe();
}
