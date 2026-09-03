package com.rpatest.orchestrator.dto;

import java.time.OffsetDateTime;

/** Mirrors the subset of LTools.Dto.Orchestrator.Assignments.AssignmentDto used by this service. */
public record AssignmentDto(
        int id,
        String name,
        String description,
        int rpaProjectId,
        AssignmentStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime stateChangedAt,
        String lastErrorMsg) {
}
