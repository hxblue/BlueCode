package com.bluecode.team.exceptions;

public final class TeamHasActiveMembersException extends TeamException {
    public TeamHasActiveMembersException(String name) {
        super("Team 仍有活跃成员，不能删除: " + name);
    }
}
