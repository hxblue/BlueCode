package com.bluecode.hook;

import java.util.List;
import java.util.Map;

public sealed interface Action permits Action.Shell, Action.Prompt, Action.Http, Action.Subagent {
    default String typeName() {
        return switch (this) {
            case Shell ignored -> "shell";
            case Prompt ignored -> "prompt";
            case Http ignored -> "http";
            case Subagent ignored -> "subagent";
        };
    }

    record Shell(String command) implements Action {
        public Shell {
            command = command == null ? "" : command;
        }
    }

    record Prompt(String text) implements Action {
        public Prompt {
            text = text == null ? "" : text;
        }
    }

    record Http(String url, String method, Map<String, String> headers, String bodyTemplate) implements Action {
        public Http {
            url = url == null ? "" : url;
            method = method == null || method.isBlank() ? "POST" : method.strip().toUpperCase();
            headers = Map.copyOf(headers == null ? Map.of() : headers);
        }
    }

    record Subagent(String agentName, String prompt) implements Action {
        public Subagent {
            agentName = agentName == null ? "" : agentName;
            prompt = prompt == null ? "" : prompt;
        }
    }
}
