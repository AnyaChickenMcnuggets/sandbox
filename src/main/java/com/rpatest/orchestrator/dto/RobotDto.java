package com.rpatest.orchestrator.dto;

/** Mirrors the subset of LTools.Dto.Orchestrator.Robots.RobotDto used by this service. */
public record RobotDto(int id, String name, RobotRunStatus status) {

    public boolean isFree() {
        return status == RobotRunStatus.IDLE;
    }
}
