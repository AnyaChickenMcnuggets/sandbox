package com.rpatest.execution.domain;

public enum RunStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    STOPPED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == STOPPED;
    }
}
