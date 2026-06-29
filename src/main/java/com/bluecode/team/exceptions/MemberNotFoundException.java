package com.bluecode.team.exceptions;

public final class MemberNotFoundException extends TeamException {
    public MemberNotFoundException(String name) {
        super("找不到 Team 成员: " + name);
    }
}
