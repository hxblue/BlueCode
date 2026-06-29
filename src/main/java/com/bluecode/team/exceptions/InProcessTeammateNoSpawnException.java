package com.bluecode.team.exceptions;

public final class InProcessTeammateNoSpawnException extends TeamException {
    public InProcessTeammateNoSpawnException() {
        super("in-process 队员不能继续派生 Team 队员");
    }
}
