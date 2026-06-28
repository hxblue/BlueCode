package com.bluecode.tool;

public record Result(String content, boolean isError) {
    public Result {
        content = content == null ? "" : content;
    }

    public static Result ok(String content) {
        return new Result(content, false);
    }

    public static Result error(String content) {
        return new Result(content, true);
    }
}
