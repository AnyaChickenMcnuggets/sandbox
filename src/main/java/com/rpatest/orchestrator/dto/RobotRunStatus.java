package com.rpatest.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Mirrors LTools.Enums.RunStatus (1-3) from orc_swagger.json — состояние самого робота (свободен /
 * занят выполнением проекта / недоступен), а не статус запуска Assignment
 * ({@link AssignmentStatus}) или реального запуска проекта ({@link RpaProjectLaunchDto}).
 */
public enum RobotRunStatus {
    UNAVAILABLE(1),
    IDLE(2),
    RUNNING(3);

    private final int code;

    RobotRunStatus(int code) {
        this.code = code;
    }

    @JsonValue
    public int getCode() {
        return code;
    }

    @JsonCreator
    public static RobotRunStatus fromCode(int code) {
        for (RobotRunStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown robot RunStatus code: " + code);
    }
}
