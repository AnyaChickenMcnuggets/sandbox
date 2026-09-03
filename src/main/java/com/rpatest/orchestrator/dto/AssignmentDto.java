package com.rpatest.orchestrator.dto;

import java.time.LocalDateTime;

/**
 * Mirrors the subset of LTools.Dto.Orchestrator.Assignments.AssignmentDto used by this service.
 * Даты у оркестратора приходят без смещения часового пояса (например
 * {@code 2026-09-03T10:22:26.305218}), поэтому {@link LocalDateTime}, а не {@code OffsetDateTime}.
 */
public record AssignmentDto(
        int id,
        String name,
        String description,
        int rpaProjectId,
        AssignmentStatus status,
        LocalDateTime startedAt,
        LocalDateTime stateChangedAt,
        String lastErrorMsg) {
}
