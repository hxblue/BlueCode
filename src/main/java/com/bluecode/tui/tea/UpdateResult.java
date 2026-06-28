package com.bluecode.tui.tea;

public record UpdateResult<M extends Model>(M model, Command command) {
}
