package com.bluecode.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchersTest {
    @Test
    void exactMatchesWholeText() throws Exception {
        Matcher matcher = Matchers.compile("=git status", true);

        assertTrue(matcher.match("git status"));
        assertFalse(matcher.match("git status -s"));
    }

    @Test
    void regexUsesFindSemantics() throws Exception {
        Matcher matcher = Matchers.compile("~^npm (install|test)$", true);

        assertTrue(matcher.match("npm install"));
        assertFalse(matcher.match("npm run dev"));
    }

    @Test
    void notCanWrapExactRegexAndGlob() throws Exception {
        assertFalse(Matchers.compile("!=foo", true).match("foo"));
        assertTrue(Matchers.compile("!=foo", true).match("bar"));
        assertFalse(Matchers.compile("!~^rm", true).match("rm -rf ."));
        assertTrue(Matchers.compile("!~^rm", true).match("ls -lh"));
        assertFalse(Matchers.compile("!git *", true).match("git status"));
    }

    @Test
    void invalidMatcherFailsAtCompileTime() {
        assertThrows(Matchers.MatcherCompileException.class, () -> Matchers.compile("", true));
        assertThrows(Matchers.MatcherCompileException.class, () -> Matchers.compile("~[invalid", true));
        assertThrows(Matchers.MatcherCompileException.class, () -> Matchers.compile("!", true));
    }
}
