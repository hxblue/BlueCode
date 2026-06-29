package com.bluecode.team.exceptions;

public final class TeamNotFoundException extends TeamException {
    public TeamNotFoundException(String name) {
        super("找不到 Team: " + name);
    }
}
