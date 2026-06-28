package com.bluecode.tool;

import java.util.regex.Pattern;

final class GlobMatcher {
    private GlobMatcher() {
    }

    static boolean matches(String glob, String path) {
        if (glob == null || glob.isBlank()) {
            return true;
        }
        String normalized = path.replace('\\', '/');
        return Pattern.compile(toRegex(glob.replace('\\', '/'))).matcher(normalized).matches();
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); ) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doublestar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doublestar) {
                    boolean followedBySlash = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                    regex.append(followedBySlash ? "(?:.*/)?" : ".*");
                    i += followedBySlash ? 3 : 2;
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else {
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
                i++;
            }
        }
        regex.append('$');
        return regex.toString();
    }
}
