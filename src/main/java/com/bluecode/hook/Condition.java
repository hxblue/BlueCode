package com.bluecode.hook;

import java.util.List;

public record Condition(CombineMode mode, List<AtomCondition> atoms) {
    public Condition {
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }
        atoms = List.copyOf(atoms == null ? List.of() : atoms);
    }
}
