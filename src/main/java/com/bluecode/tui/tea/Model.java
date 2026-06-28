package com.bluecode.tui.tea;

public interface Model {
    Command init();

    UpdateResult<? extends Model> update(Message msg);

    String view();

    default CursorPlacement cursorPlacement() {
        return CursorPlacement.afterView();
    }

    default String dumpHistory() {
        return "";
    }
}
