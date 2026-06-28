package com.bluecode.tui.tea;

public record MouseMessage(String kind, int x, int y) implements Message {
}
