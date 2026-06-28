package com.bluecode.agent;

public record ToolEvent(String name, String args, Phase phase, String result, boolean isError) {
}
