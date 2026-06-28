package com.bluecode.tui;

public final class SpinnerVerbs {
    private static final String[] FRAMES = {"Imagining", "Thinking", "Composing", "Streaming"};

    private SpinnerVerbs() {
    }

    public static String frame(int index) {
        return FRAMES[Math.floorMod(index, FRAMES.length)];
    }
}
