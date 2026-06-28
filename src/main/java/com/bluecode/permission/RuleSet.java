package com.bluecode.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RuleSet {
    private final List<Rule> allow;
    private final List<Rule> deny;

    public RuleSet(List<Rule> allow, List<Rule> deny) {
        this.allow = new ArrayList<>(allow == null ? List.of() : allow);
        this.deny = new ArrayList<>(deny == null ? List.of() : deny);
    }

    public static RuleSet empty() {
        return new RuleSet(List.of(), List.of());
    }

    public Optional<Decision> match(String friendly, String target) {
        boolean pathStyle = !"Bash".equals(friendly);
        for (Rule rule : deny) {
            if (matches(rule, friendly, target, pathStyle)) {
                return Optional.of(Decision.DENY);
            }
        }
        for (Rule rule : allow) {
            if (matches(rule, friendly, target, pathStyle)) {
                return Optional.of(Decision.ALLOW);
            }
        }
        return Optional.empty();
    }

    void addAllow(Rule rule) {
        if (rule != null && allow.stream().noneMatch(existing -> existing.equals(rule))) {
            allow.add(rule);
        }
    }

    private boolean matches(Rule rule, String friendly, String target, boolean pathStyle) {
        return rule.tool().equals(friendly) && rule.matches(target);
    }
}
