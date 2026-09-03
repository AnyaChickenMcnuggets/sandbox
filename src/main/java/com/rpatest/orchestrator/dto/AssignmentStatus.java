package com.rpatest.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Mirrors LTools.Enums.AssignmentStatus (0-4) from orc_swagger.json. */
public enum AssignmentStatus {
    NEW(0),
    RUNNING(1),
    COMPLETE(2),
    PAUSED(3),
    ERROR(4);

    private final int code;

    AssignmentStatus(int code) {
        this.code = code;
    }

    @JsonValue
    public int getCode() {
        return code;
    }

    @JsonCreator
    public static AssignmentStatus fromCode(int code) {
        for (AssignmentStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Неизвестный AssignmentStatus код: " + code);
    }

    public boolean isTerminal() {
        return this == COMPLETE || this == ERROR;
    }
}
