package com.bluecode.hook;

import com.bluecode.permission.Matcher;

public record AtomCondition(String field, Matcher matcher) {
    public AtomCondition {
        field = field == null ? "" : field.strip();
        if (field.isBlank()) {
            throw new IllegalArgumentException("field 不能为空");
        }
        if (matcher == null) {
            throw new IllegalArgumentException("matcher 不能为空");
        }
    }
}
