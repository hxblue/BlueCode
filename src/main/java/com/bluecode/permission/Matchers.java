package com.bluecode.permission;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class Matchers {
    private Matchers() {
    }

    public static Matcher compile(String pattern, boolean command) throws MatcherCompileException {
        if (pattern == null || pattern.isEmpty()) {
            throw new MatcherCompileException("empty matcher pattern");
        }
        char prefix = pattern.charAt(0);
        if (prefix == '=') {
            return new ExactMatcher(pattern.substring(1));
        }
        if (prefix == '~') {
            String regex = pattern.substring(1);
            if (regex.isEmpty()) {
                throw new MatcherCompileException("empty regex matcher");
            }
            try {
                return new RegexMatcher(Pattern.compile(regex), regex);
            } catch (PatternSyntaxException e) {
                throw new MatcherCompileException("invalid regex: " + e.getMessage(), e);
            }
        }
        if (prefix == '!') {
            String inner = pattern.substring(1);
            if (inner.isEmpty()) {
                throw new MatcherCompileException("not matcher requires inner pattern");
            }
            return new NotMatcher(compile(inner, command));
        }
        return new GlobMatcher(pattern, command);
    }

    public static final class MatcherCompileException extends Exception {
        public MatcherCompileException(String message) {
            super(message);
        }

        public MatcherCompileException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
