package com.bluecode.permission;

import java.util.Optional;

public record Rule(String tool, String pattern, Matcher matcher, boolean allow) {
    public Rule {
        tool = tool == null ? "" : tool.strip();
        pattern = pattern == null ? "" : pattern.strip();
    }

    public static Optional<Rule> parse(String text, boolean allow) {
        try {
            return Optional.of(parseOrThrow(text, allow));
        } catch (RuleParseException e) {
            return Optional.empty();
        }
    }

    public static Rule parseOrThrow(String text, boolean allow) throws RuleParseException {
        if (text == null || text.isBlank()) {
            throw new RuleParseException("empty rule");
        }
        String value = text.strip();
        int open = value.indexOf('(');
        if (open < 0) {
            return new Rule(value, "", null, allow);
        }
        if (!value.endsWith(")") || open == 0) {
            throw new RuleParseException("invalid rule syntax");
        }
        String tool = value.substring(0, open).strip();
        String pattern = value.substring(open + 1, value.length() - 1);
        if (tool.isBlank()) {
            throw new RuleParseException("tool name is empty");
        }
        Matcher matcher = null;
        if (!pattern.isBlank()) {
            try {
                matcher = Matchers.compile(pattern, "Bash".equals(tool));
            } catch (Matchers.MatcherCompileException e) {
                throw new RuleParseException(e.getMessage(), e);
            }
        }
        return new Rule(tool, pattern, matcher, allow);
    }

    public boolean matches(String target) {
        return matcher == null || matcher.match(target == null ? "" : target);
    }

    static boolean matchPattern(String pattern, String target, boolean pathStyle) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        String normalizedPattern = normalizeForMatch(pattern, pathStyle);
        String normalizedTarget = normalizeForMatch(target == null ? "" : target, pathStyle);
        return normalizedTarget.matches(toRegex(normalizedPattern, pathStyle));
    }

    public static boolean matchPattern(String pattern, String target) {
        boolean pathStyle = (pattern != null && (pattern.contains("/") || pattern.contains("\\")))
                || (target != null && (target.contains("/") || target.contains("\\")));
        return matchPattern(pattern, target, pathStyle);
    }

    private static String normalizeForMatch(String value, boolean pathStyle) {
        return pathStyle ? value.replace('\\', '/') : value;
    }

    private static String toRegex(String glob, boolean pathStyle) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '\\' && i + 1 < glob.length()) {
                appendLiteral(regex, glob.charAt(++i));
            } else if (ch == '*') {
                boolean doublestar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doublestar) {
                    i++;
                }
                regex.append(pathStyle && !doublestar ? "[^/]*" : ".*");
            } else if (ch == '?') {
                regex.append(pathStyle ? "[^/]" : ".");
            } else {
                appendLiteral(regex, ch);
            }
        }
        regex.append("$");
        return regex.toString();
    }

    private static void appendLiteral(StringBuilder regex, char ch) {
        if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
            regex.append('\\');
        }
        regex.append(ch);
    }

    public static final class RuleParseException extends Exception {
        public RuleParseException(String message) {
            super(message);
        }

        public RuleParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
