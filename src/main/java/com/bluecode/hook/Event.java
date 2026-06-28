package com.bluecode.hook;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public enum Event {
    SESSION_START("SessionStart"),
    SESSION_END("SessionEnd"),
    SESSION_RESUME("SessionResume"),
    USER_PROMPT_SUBMIT("UserPromptSubmit"),
    STOP("Stop"),
    PRE_USER_MESSAGE("PreUserMessage"),
    PRE_TOOL_USE("PreToolUse"),
    POST_TOOL_USE("PostToolUse"),
    PRE_COMPACT("PreCompact"),
    POST_COMPACT("PostCompact"),
    NOTIFICATION("Notification");

    private static final Map<String, Event> BY_NORMALIZED = Map.ofEntries(
            Map.entry(normalize("SessionStart"), SESSION_START),
            Map.entry(normalize("SessionEnd"), SESSION_END),
            Map.entry(normalize("SessionResume"), SESSION_RESUME),
            Map.entry(normalize("UserPromptSubmit"), USER_PROMPT_SUBMIT),
            Map.entry(normalize("Stop"), STOP),
            Map.entry(normalize("PreUserMessage"), PRE_USER_MESSAGE),
            Map.entry(normalize("PreToolUse"), PRE_TOOL_USE),
            Map.entry(normalize("PostToolUse"), POST_TOOL_USE),
            Map.entry(normalize("PreCompact"), PRE_COMPACT),
            Map.entry(normalize("PostCompact"), POST_COMPACT),
            Map.entry(normalize("Notification"), NOTIFICATION)
    );

    private final String wireName;

    Event(String wireName) {
        this.wireName = wireName;
    }

    public boolean isBlocking() {
        return this == PRE_TOOL_USE || this == USER_PROMPT_SUBMIT;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<Event> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_NORMALIZED.get(normalize(text)));
    }

    private static String normalize(String text) {
        String value = text == null ? "" : text;
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                normalized.append(Character.toLowerCase(ch));
            }
        }
        return normalized.toString().toLowerCase(Locale.ROOT);
    }
}
