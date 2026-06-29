package com.bluecode.team.exceptions;

public final class MemberExistsException extends TeamException {
    public MemberExistsException(String name) {
        super("Team 成员已存在: " + name);
    }
}
