package com.bluecode.tui.tea;

public record CursorPlacement(int upFromAfterView, int column) {
    public static CursorPlacement afterView() {
        return new CursorPlacement(0, 1);
    }
}
