package com.bluecode.hook;

public final class ConditionEvaluator {
    private ConditionEvaluator() {
    }

    public static boolean evaluate(Condition condition, Payload payload) {
        if (condition == null) {
            return true;
        }
        Payload effectivePayload = payload == null ? new Payload(java.util.Map.of()) : payload;
        return switch (condition.mode()) {
            case ALL_OF -> condition.atoms().stream()
                    .allMatch(atom -> atom.matcher().match(effectivePayload.getByPath(atom.field())));
            case ANY_OF -> condition.atoms().stream()
                    .anyMatch(atom -> atom.matcher().match(effectivePayload.getByPath(atom.field())));
        };
    }
}
